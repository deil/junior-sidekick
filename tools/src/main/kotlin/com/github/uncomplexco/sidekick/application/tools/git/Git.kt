package com.github.uncomplexco.sidekick.application.tools.git

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.adapters.jgit.JGitRepository
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.PROJECT_ROOT
import com.github.uncomplexco.sidekick.application.utils.Loggers
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

private val PREFERRED_BRANCHES = listOf("develop", "master", "main")
private val SCP_LIKE_URL_RE = Regex("^git@([^/:\\s]+):(.+)$")

@Component
@ConfigurationProperties(prefix = "agent.tools.git")
class GitToolConfig {
    var sshKeyFile: String? = null
    var github: GitProviderConfig = GitProviderConfig()
    var bitbucket: GitProviderConfig = GitProviderConfig()
}

class GitProviderConfig {
    var sshKeyFile: String? = null
}

class GitTools(
    private val config: GitToolConfig,
    private val virtualPaths: VirtualPaths,
    private val git: GitRepository = JGitRepository(),
) : ToolSet {
    private val logger = LoggerFactory.getLogger(Loggers.TOOLS.name + ".git")

    @Tool("git__clone")
    @LLMDescription(
        "Clone or fetch and fast-forward a private Git repository into the project workspace"
    )
    fun clone(
        @LLMDescription("Git repository URL. Both SSH and HTTPS clone URLs are supported")
        url: String,
        @LLMDescription("Destination folder path. Must be under /data/project") path: String,
    ): GitCloneResult {
        validate(url.isNotBlank()) { "url is required" }
        validate(path.isNotBlank()) { "path is required" }

        val repository = parseRepositoryUrl(url)
        val sshKeyFile = sshKeyFile(repository.provider)
        val checkout = resolveWritablePath(path)

        val state =
            try {
                if (Files.exists(checkout, LinkOption.NOFOLLOW_LINKS)) {
                    cloneOrFetchExisting(repository, sshKeyFile, checkout)
                } else {
                    prepareMissingDestination(checkout)
                    git.clone(
                        repository.sshUrl,
                        sshKeyFile,
                        checkout,
                        virtualPaths.projectRoot,
                        PREFERRED_BRANCHES,
                    )
                }
            } catch (error: IllegalArgumentException) {
                logger.error("Git clone failed path={}", path, error)
                throw ToolException.ValidationFailure(error.message ?: "Invalid git clone request")
            } catch (error: Exception) {
                logger.error("Git clone failed path={}", path, error)
                throw ToolException.ValidationFailure(error.message ?: "Git clone failed")
            }
        logCloneOutcome(state)
        return state.toToolResult(virtualPaths)
    }

    @Tool("git__push")
    @LLMDescription(
        "Update remote refs along with associated objects. Use instead of direct 'git push' invocation for private repositories"
    )
    fun push(
        @LLMDescription("Repository folder path") path: String,
        @LLMDescription(
            "What destination ref to update with what source object. Equivalent to 'git push origin <branch>'. Defaults to the current branch upstream"
        )
        refspec: String? = null,
        @LLMDescription(
            "Push all branches (i.e. refs under refs/heads/); cannot be used with other <refspec>. Equivalent to 'git push --all"
        )
        all: Boolean = false,
        @LLMDescription(
            "All refs under refs/tags are pushed, in addition to refspecs explicitly listed in <refspec>. Equivalent to 'git push --tags'"
        )
        tags: Boolean = false,
    ): GitPushResult {
        validate(path.isNotBlank()) { "'path' is required" }
        validate(refspec == null || refspec.isNotBlank()) { "'branch' must not be blank" }
        validate(!all || refspec == null) { "'all' cannot be used with 'refspec'" }

        val checkout = resolveProjectPath(path)
        validate(!Files.isSymbolicLink(checkout)) { "'path' must not be a symbolic link" }
        validate(git.isGitRepository(checkout)) { "'path' is not a Git repository" }

        val plan =
            try {
                git.pushPlan(checkout, refspec)
            } catch (error: IllegalArgumentException) {
                logger.error("Git push failed path={}", path, error)
                throw ToolException.ValidationFailure(error.message ?: "Invalid git push request")
            }
        if (plan.status != null) {
            val state = plan.toState(plan.status, plan.message)
            logPushOutcome(state)
            return state.toToolResult(virtualPaths)
        }

        val remoteUrl =
            plan.remoteUrl
                ?: throw ToolException.ValidationFailure(
                    "Git repository upstream remote has no URL"
                )
        val repository = parseRepositoryUrl(remoteUrl)
        val sshKeyFile = sshKeyFile(repository.provider)
        val remote =
            plan.remote
                ?: throw ToolException.ValidationFailure("Git repository has no upstream remote")

        val state =
            try {
                useSshRemote(checkout, remote, remoteUrl, repository)
                git.push(checkout, sshKeyFile, refspec, all, tags)
            } catch (error: IllegalArgumentException) {
                logger.error("Git push failed path={}", path, error)
                throw ToolException.ValidationFailure(error.message ?: "Invalid git push request")
            } catch (error: Exception) {
                logger.error("Git push failed path={}", path, error)
                throw error
            }
        logPushOutcome(state)
        return state.toToolResult(virtualPaths)
    }

    @Tool("git__pull")
    @LLMDescription(
        "Fetch from and integrate with another repository or a local branch. Use instead of direct 'git pull' invocation for private repositories"
    )
    fun pull(
        @LLMDescription("Repository folder path") path: String,
        @LLMDescription("Name of a remote that is the source of a fetch or pull operation")
        remote: String,
        @LLMDescription("Specifies which refs to fetch and which local refs to update")
        refspec: String,
    ): GitPullResult {
        validate(path.isNotBlank()) { "'path' is required" }
        validate(remote.isNotBlank()) { "'remote' is required" }
        validate(refspec.isNotBlank()) { "'refspec' is required" }

        val checkout = resolveProjectPath(path)
        validate(!Files.isSymbolicLink(checkout)) { "'path' must not be a symbolic link" }
        validate(git.isGitRepository(checkout)) { "'path' is not a Git repository" }

        val remoteUrl =
            git.remoteUrl(checkout, remote)
                ?: throw ToolException.ValidationFailure("Git repository remote has no URL")
        val repository = parseRepositoryUrl(remoteUrl)
        val sshKeyFile = sshKeyFile(repository.provider)

        val state =
            try {
                useSshRemote(checkout, remote, remoteUrl, repository)
                git.pull(checkout, sshKeyFile, remote, refspec)
            } catch (error: IllegalArgumentException) {
                logger.error("Git pull failed path={} remote={}", path, remote, error)
                throw ToolException.ValidationFailure(error.message ?: "Invalid git pull request")
            } catch (error: Exception) {
                logger.error("Git pull failed path={} remote={}", path, remote, error)
                throw error
            }
        logPullOutcome(state)
        return state.toToolResult(virtualPaths)
    }

    private fun cloneOrFetchExisting(
        repository: GitRepositoryUrl,
        sshKeyFile: String,
        checkout: Path,
    ): GitRepositoryState {
        if (Files.isSymbolicLink(checkout)) {
            throw ToolException.ValidationFailure(
                "Path must not be a symbolic link: ${virtualPaths.virtualPath(checkout.pathString)}"
            )
        }
        if (!git.isGitRepository(checkout)) {
            if (
                Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS) && isEmptyDirectory(checkout)
            ) {
                return git.clone(
                    repository.sshUrl,
                    sshKeyFile,
                    checkout,
                    virtualPaths.projectRoot,
                    PREFERRED_BRANCHES,
                )
            }
            throw ToolException.ValidationFailure(
                "Path exists but is not an empty directory or Git repository: ${virtualPaths.virtualPath(checkout.pathString)}"
            )
        }

        val origin =
            git.originUrl(checkout)
                ?: throw ToolException.ValidationFailure(
                    "Git repository has no origin remote: ${virtualPaths.virtualPath(checkout.pathString)}"
                )
        val originRepository = parseRepositoryUrl(origin)
        if (originRepository.canonical != repository.canonical) {
            throw ToolException.ValidationFailure(
                "Git repository origin does not match requested URL: ${virtualPaths.virtualPath(checkout.pathString)}"
            )
        }
        useSshRemote(checkout, "origin", origin, originRepository)

        return git.fetch(checkout, sshKeyFile)
    }

    private fun useSshRemote(
        checkout: Path,
        remote: String,
        currentUrl: String,
        repository: GitRepositoryUrl,
    ) {
        if (currentUrl != repository.sshUrl) {
            git.setRemoteUrl(checkout, remote, repository.sshUrl)
            logger.info(
                "Converted Git remote to SSH path={} remote={} provider={} source_url={} target_url={}",
                virtualPaths.virtualPath(checkout.pathString),
                remote,
                repository.provider.displayName,
                currentUrl,
                repository.sshUrl,
            )
        }
    }

    private fun logCloneOutcome(state: GitRepositoryState) {
        val path = virtualPaths.virtualPath(state.path.pathString)
        when (state.status) {
            GitRepositoryStatus.CLONED,
            GitRepositoryStatus.FETCHED_FAST_FORWARDED,
            GitRepositoryStatus.FETCHED_UP_TO_DATE ->
                logger.info("Git clone operation completed path={} status={}", path, state.status)
            else -> logger.error("Git clone operation failed path={} status={}", path, state.status)
        }
    }

    private fun logPushOutcome(state: GitPushState) {
        val path = virtualPaths.virtualPath(state.path.pathString)
        when (state.status) {
            GitPushStatus.PUSHED,
            GitPushStatus.UP_TO_DATE ->
                logger.info(
                    "Git push completed path={} status={} remote={} branch={}",
                    path,
                    state.status,
                    state.remote,
                    state.branch,
                )
            else ->
                logger.error(
                    "Git push failed path={} status={} remote={} branch={}",
                    path,
                    state.status,
                    state.remote,
                    state.branch,
                )
        }
    }

    private fun logPullOutcome(state: GitPullState) {
        val path = virtualPaths.virtualPath(state.path.pathString)
        when (state.status) {
            GitPullStatus.FAST_FORWARDED,
            GitPullStatus.UP_TO_DATE,
            GitPullStatus.MERGED ->
                logger.info(
                    "Git pull completed path={} status={} remote={} branch={}",
                    path,
                    state.status,
                    state.remote,
                    state.branch,
                )
            else ->
                logger.error(
                    "Git pull failed path={} status={} remote={} branch={}",
                    path,
                    state.status,
                    state.remote,
                    state.branch,
                )
        }
    }

    private fun prepareMissingDestination(checkout: Path) {
        val parent =
            checkout.parent
                ?: throw ToolException.ValidationFailure("Path must name a destination folder")
        ensureNoSymlinksFromWritableRoot(parent)
        Files.createDirectories(parent)
    }

    private fun resolveWritablePath(path: String): Path {
        val root =
            virtualPaths.roots.firstOrNull { root ->
                path == root.virtual || path.startsWith("${root.virtual}/")
            }
                ?: throw ToolException.ValidationFailure(
                    "Path must be under a writable workspace root"
                )
        if (!root.writable) {
            throw ToolException.ValidationFailure("Path must be under a writable workspace root")
        }

        val relative = path.removePrefix(root.virtual).trimStart('/')
        if (relative.isBlank()) {
            throw ToolException.ValidationFailure("Path must name a destination folder")
        }

        val rootPath = root.real.toAbsolutePath().normalize()
        val checkout = root.real.resolve(relative).toAbsolutePath().normalize()
        if (!checkout.startsWith(rootPath)) {
            throw ToolException.ValidationFailure("Path must be under a writable workspace root")
        }
        return checkout
    }

    private fun resolveProjectPath(path: String): Path {
        if (path != PROJECT_ROOT && !path.startsWith("$PROJECT_ROOT/")) {
            throw ToolException.ValidationFailure("Path must be under $PROJECT_ROOT")
        }

        val relative = path.removePrefix(PROJECT_ROOT).trimStart('/')
        val checkout = virtualPaths.projectRoot.resolve(relative).normalize()
        val projectRoot = virtualPaths.projectRoot.toAbsolutePath().normalize()
        val absoluteCheckout = checkout.toAbsolutePath().normalize()
        if (absoluteCheckout == projectRoot || !absoluteCheckout.startsWith(projectRoot)) {
            throw ToolException.ValidationFailure("Path must be under $PROJECT_ROOT")
        }
        return absoluteCheckout
    }

    private fun ensureNoSymlinksFromProjectRoot(path: Path) {
        val projectRoot = virtualPaths.projectRoot.toAbsolutePath().normalize()
        val absolutePath = path.toAbsolutePath().normalize()
        if (!absolutePath.startsWith(projectRoot)) {
            throw ToolException.ValidationFailure("Path must be under $PROJECT_ROOT")
        }

        var current = projectRoot
        val relative = projectRoot.relativize(absolutePath)
        for (segment in relative) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw ToolException.ValidationFailure(
                    "Path must not traverse a symbolic link: ${virtualPaths.virtualPath(current.pathString)}"
                )
            }
        }
    }

    private fun ensureNoSymlinksFromWritableRoot(path: Path) {
        val absolutePath = path.toAbsolutePath().normalize()
        val root =
            virtualPaths.roots.firstOrNull { root -> root.writable && root.contains(absolutePath) }
                ?: throw ToolException.ValidationFailure(
                    "Path must be under a writable workspace root"
                )
        val rootPath = root.real.toAbsolutePath().normalize()

        var current = rootPath
        val relative = rootPath.relativize(absolutePath)
        for (segment in relative) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw ToolException.ValidationFailure(
                    "Path must not traverse a symbolic link: ${virtualPaths.virtualPath(current.pathString)}"
                )
            }
        }
    }

    private fun sshKeyFile(provider: GitProvider): String {
        val (value, property) =
            when (provider) {
                GitProvider.GITHUB ->
                    config.github.sshKeyFile to "agent.tools.git.github.ssh-key-file"
                GitProvider.BITBUCKET ->
                    config.bitbucket.sshKeyFile to "agent.tools.git.bitbucket.ssh-key-file"
                GitProvider.AZURE_DEVOPS,
                GitProvider.OTHER -> config.sshKeyFile to "agent.tools.git.ssh-key-file"
            }
        return value?.takeIf { it.isNotBlank() }
            ?: throw ToolException.ValidationFailure(
                "${provider.displayName} SSH key is not configured: $property"
            )
    }
}

interface GitRepository {
    fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState

    fun fetch(
        checkout: Path,
        sshKeyFile: String,
    ): GitRepositoryState

    fun isGitRepository(checkout: Path): Boolean

    fun originUrl(checkout: Path): String?

    fun remoteUrl(
        checkout: Path,
        remote: String,
    ): String?

    fun setRemoteUrl(
        checkout: Path,
        remote: String,
        url: String,
    )

    fun pushPlan(
        checkout: Path,
        branch: String?,
    ): GitPushPlan

    fun push(
        checkout: Path,
        sshKeyFile: String,
        branch: String?,
        all: Boolean,
        tags: Boolean,
    ): GitPushState

    fun pull(
        checkout: Path,
        sshKeyFile: String,
        remote: String,
        refspec: String,
    ): GitPullState
}

@Serializable
data class GitCloneResult(
    val path: String,
    val branch: String,
    val commit_hash: String,
    val status: String,
)

data class GitRepositoryState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val status: GitRepositoryStatus,
)

@Serializable
data class GitPushResult(
    val path: String,
    val branch: String,
    val commit_hash: String,
    val remote: String?,
    val upstream: String?,
    val dirty: Boolean,
    val status: String,
    val message: String,
)

data class GitPushPlan(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val dirty: Boolean,
    val remote: String?,
    val upstream: String?,
    val remoteUrl: String?,
    val status: GitPushStatus?,
    val message: String,
) {
    fun toState(
        status: GitPushStatus,
        message: String,
    ): GitPushState =
        GitPushState(
            path = path,
            branch = branch,
            commitHash = commitHash,
            dirty = dirty,
            remote = remote,
            upstream = upstream,
            status = status,
            message = message,
        )
}

data class GitPushState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val dirty: Boolean,
    val remote: String?,
    val upstream: String?,
    val status: GitPushStatus,
    val message: String,
)

@Serializable
data class GitPullResult(
    val path: String,
    val branch: String,
    val commit_hash: String,
    val remote: String,
    val upstream: String,
    val status: String,
    val message: String,
)

data class GitPullState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val remote: String,
    val upstream: String,
    val status: GitPullStatus,
    val message: String,
)

enum class GitRepositoryStatus {
    CLONED,
    FETCHED_FAST_FORWARDED,
    FETCHED_UP_TO_DATE,
    FETCHED_DIVERGED,
    FETCHED_FAST_FORWARD_FAILED,
    FETCHED_DETACHED_HEAD,
    FETCHED_NO_REMOTE_BRANCH,
}

enum class GitPushStatus {
    PUSHED,
    UP_TO_DATE,
    REJECTED_NON_FAST_FORWARD,
    REJECTED_REMOTE_CHANGED,
    REJECTED_OTHER_REASON,
    NON_EXISTING_REMOTE_REF,
    AWAITING_REPORT,
    NOT_ATTEMPTED,
    FAILED,
    DETACHED_HEAD,
    NO_UPSTREAM,
    NO_REMOTE,
}

enum class GitPullStatus {
    FAST_FORWARDED,
    UP_TO_DATE,
    MERGED,
    CONFLICTING,
    CHECKOUT_CONFLICT,
    FAILED,
}

private fun GitRepositoryState.toToolResult(virtualPaths: VirtualPaths): GitCloneResult =
    GitCloneResult(
        path = virtualPaths.virtualPath(path.pathString),
        branch = branch,
        commit_hash = commitHash,
        status = status.name.lowercase(),
    )

private fun GitPushState.toToolResult(virtualPaths: VirtualPaths): GitPushResult =
    GitPushResult(
        path = virtualPaths.virtualPath(path.pathString),
        branch = branch,
        commit_hash = commitHash,
        remote = remote,
        upstream = upstream,
        dirty = dirty,
        status = status.name.lowercase(),
        message = message,
    )

private fun GitPullState.toToolResult(virtualPaths: VirtualPaths): GitPullResult =
    GitPullResult(
        path = virtualPaths.virtualPath(path.pathString),
        branch = branch,
        commit_hash = commitHash,
        remote = remote,
        upstream = upstream,
        status = status.name.lowercase(),
        message = message,
    )

private fun isEmptyDirectory(path: Path): Boolean =
    Files.list(path).use { entries -> entries.findAny().isEmpty }

private fun parseRepositoryUrl(url: String): GitRepositoryUrl {
    SCP_LIKE_URL_RE.matchEntire(url)?.let { match ->
        val host = match.groupValues[1]
        val repositoryPath = match.groupValues[2]
        return repositoryUrl(host, repositoryPath)
    }

    val uri =
        try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            throw ToolException.ValidationFailure("Unsupported Git repository URL")
        }
    val host =
        uri.host?.lowercase()
            ?: throw ToolException.ValidationFailure("Unsupported Git repository URL")
    if (uri.scheme !in setOf("https", "ssh")) {
        throw ToolException.ValidationFailure("Git repository URL must use SSH or HTTPS")
    }

    return repositoryUrl(host, uri.path)
}

private fun repositoryUrl(
    host: String,
    path: String,
): GitRepositoryUrl {
    val normalizedHost = host.lowercase()
    if (normalizedHost == "dev.azure.com" || normalizedHost == "ssh.dev.azure.com") {
        return azureDevOpsRepositoryUrl(normalizedHost, path)
    }

    val parts = path.trim('/').split('/').filter { it.isNotBlank() }
    if (parts.size < 2) {
        throw ToolException.ValidationFailure(
            "Git repository URL must identify owner and repository"
        )
    }
    val repository = parts.last().removeSuffix(".git")
    if (repository.isBlank()) {
        throw ToolException.ValidationFailure(
            "Git repository URL must identify owner and repository"
        )
    }
    val repositoryPath = (parts.dropLast(1) + repository).joinToString("/")

    val provider =
        when (normalizedHost) {
            "github.com" -> GitProvider.GITHUB
            "bitbucket.org" -> GitProvider.BITBUCKET
            else -> GitProvider.OTHER
        }

    return GitRepositoryUrl(
        provider = provider,
        host = normalizedHost,
        repositoryPath = repositoryPath,
        sshUrl = "git@$normalizedHost:$repositoryPath.git",
    )
}

private fun azureDevOpsRepositoryUrl(
    host: String,
    path: String,
): GitRepositoryUrl {
    val parts = path.trim('/').split('/').filter { it.isNotBlank() }
    val coordinates =
        when {
            host == "dev.azure.com" &&
                parts.size == 4 &&
                parts[2].equals("_git", ignoreCase = true) ->
                listOf(parts[0], parts[1], parts[3].removeSuffix(".git"))
            host == "ssh.dev.azure.com" &&
                parts.size == 4 &&
                parts[0].equals("v3", ignoreCase = true) ->
                listOf(parts[1], parts[2], parts[3].removeSuffix(".git"))
            else -> throw ToolException.ValidationFailure("Unsupported Azure DevOps repository URL")
        }
    if (coordinates.any { it.isBlank() }) {
        throw ToolException.ValidationFailure(
            "Azure DevOps URL must identify organization, project, and repository"
        )
    }
    val repositoryPath = coordinates.joinToString("/")

    return GitRepositoryUrl(
        provider = GitProvider.AZURE_DEVOPS,
        host = "dev.azure.com",
        repositoryPath = repositoryPath,
        sshUrl = "git@ssh.dev.azure.com:v3/$repositoryPath",
    )
}

private data class GitRepositoryUrl(
    val provider: GitProvider,
    val host: String,
    val repositoryPath: String,
    val sshUrl: String,
) {
    val canonical = "$host/${repositoryPath.lowercase()}"
}

private enum class GitProvider(val displayName: String) {
    GITHUB("GitHub"),
    BITBUCKET("Bitbucket"),
    AZURE_DEVOPS("Azure DevOps"),
    OTHER("Git provider"),
}
