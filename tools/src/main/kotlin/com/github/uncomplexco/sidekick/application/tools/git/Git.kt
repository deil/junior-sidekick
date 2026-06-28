package com.github.uncomplexco.sidekick.application.tools.git

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.adapters.jgit.JGitRepository
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.PROJECT_ROOT
import kotlinx.serialization.Serializable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.pathString

private val PREFERRED_BRANCHES = listOf("develop", "master", "main")
private val SCP_LIKE_URL_RE = Regex("^git@(github\\.com|bitbucket\\.org):([^/]+)/(.+)$")

@Component
@ConfigurationProperties(prefix = "agent.tools.git")
class GitToolConfig {
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
    @Tool("git__clone")
    @LLMDescription("Clone or fetch and fast-forward a private GitHub or Bitbucket repository into the project workspace")
    fun clone(
        @LLMDescription("GitHub or Bitbucket repository URL. SSH and HTTPS clone URLs are supported")
        url: String,
        @LLMDescription("Destination folder path. Must be under /data/project")
        path: String,
    ): GitCloneResult {
        validate(url.isNotBlank()) { "url is required" }
        validate(path.isNotBlank()) { "path is required" }

        val repository = parseRepositoryUrl(url)
        val sshKeyFile = sshKeyFile(repository.provider)
        val checkout = resolveProjectPath(path)

        return try {
            if (Files.exists(checkout, LinkOption.NOFOLLOW_LINKS)) {
                cloneOrFetchExisting(repository, sshKeyFile, checkout)
            } else {
                prepareMissingDestination(checkout)
                git.clone(repository.sshUrl, sshKeyFile, checkout, virtualPaths.projectRoot, PREFERRED_BRANCHES)
            }
        } catch (error: IllegalArgumentException) {
            throw ToolException.ValidationFailure(error.message ?: "Invalid git clone request")
        } catch (error: Exception) {
            throw ToolException.ValidationFailure(error.message ?: "Git clone failed")
        }.toToolResult(virtualPaths)
    }

    private fun cloneOrFetchExisting(
        repository: GitRepositoryUrl,
        sshKeyFile: String,
        checkout: Path,
    ): GitRepositoryState {
        if (Files.isSymbolicLink(checkout)) {
            throw ToolException.ValidationFailure("Path must not be a symbolic link: ${virtualPaths.virtualPath(checkout.pathString)}")
        }
        if (!git.isGitRepository(checkout)) {
            if (Files.isDirectory(checkout, LinkOption.NOFOLLOW_LINKS) && isEmptyDirectory(checkout)) {
                return git.clone(repository.sshUrl, sshKeyFile, checkout, virtualPaths.projectRoot, PREFERRED_BRANCHES)
            }
            throw ToolException.ValidationFailure(
                "Path exists but is not an empty directory or Git repository: ${virtualPaths.virtualPath(checkout.pathString)}",
            )
        }

        val origin =
            git.originUrl(checkout)
                ?: throw ToolException.ValidationFailure(
                    "Git repository has no origin remote: ${virtualPaths.virtualPath(checkout.pathString)}",
                )
        val originRepository = parseRepositoryUrl(origin)
        if (originRepository.canonical != repository.canonical) {
            throw ToolException.ValidationFailure(
                "Git repository origin does not match requested URL: ${virtualPaths.virtualPath(checkout.pathString)}",
            )
        }

        return git.fetch(checkout, sshKeyFile)
    }

    private fun prepareMissingDestination(checkout: Path) {
        val parent = checkout.parent ?: throw ToolException.ValidationFailure("Path must be under $PROJECT_ROOT")
        ensureNoSymlinksFromProjectRoot(parent)
        Files.createDirectories(parent)
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
                    "Path must not traverse a symbolic link: ${virtualPaths.virtualPath(current.pathString)}",
                )
            }
        }
    }

    private fun sshKeyFile(provider: GitProvider): String {
        val value =
            when (provider) {
                GitProvider.GITHUB -> config.github.sshKeyFile
                GitProvider.BITBUCKET -> config.bitbucket.sshKeyFile
            }
        return value?.takeIf { it.isNotBlank() }
            ?: throw ToolException.ValidationFailure(
                "${provider.displayName} SSH key is not configured: agent.tools.git.${provider.configKey}.ssh-key-file",
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

enum class GitRepositoryStatus {
    CLONED,
    FETCHED_FAST_FORWARDED,
    FETCHED_UP_TO_DATE,
    FETCHED_DIVERGED,
    FETCHED_FAST_FORWARD_FAILED,
    FETCHED_DETACHED_HEAD,
    FETCHED_NO_REMOTE_BRANCH,
}

private fun GitRepositoryState.toToolResult(virtualPaths: VirtualPaths): GitCloneResult =
    GitCloneResult(
        path = virtualPaths.virtualPath(path.pathString),
        branch = branch,
        commit_hash = commitHash,
        status = status.name.lowercase(),
    )

private fun isEmptyDirectory(path: Path): Boolean = Files.list(path).use { entries -> entries.findAny().isEmpty }

private fun parseRepositoryUrl(url: String): GitRepositoryUrl {
    SCP_LIKE_URL_RE.matchEntire(url)?.let { match ->
        val host = match.groupValues[1]
        val owner = match.groupValues[2]
        val repository = match.groupValues[3].removeSuffix(".git")
        return repositoryUrl(host, owner, repository)
    }

    val uri =
        try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            throw ToolException.ValidationFailure("Unsupported Git repository URL")
        }
    val host = uri.host?.lowercase() ?: throw ToolException.ValidationFailure("Unsupported Git repository URL")
    if (uri.scheme !in setOf("https", "ssh")) {
        throw ToolException.ValidationFailure("Git repository URL must use SSH or HTTPS")
    }

    val parts =
        uri.path
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
    if (parts.size != 2) {
        throw ToolException.ValidationFailure("Git repository URL must identify owner and repository")
    }
    return repositoryUrl(host, parts[0], parts[1].removeSuffix(".git"))
}

private fun repositoryUrl(
    host: String,
    owner: String,
    repository: String,
): GitRepositoryUrl {
    val provider =
        when (host.lowercase()) {
            "github.com" -> GitProvider.GITHUB
            "bitbucket.org" -> GitProvider.BITBUCKET
            else -> throw ToolException.ValidationFailure("Only github.com and bitbucket.org repositories are supported")
        }
    if (owner.isBlank() || repository.isBlank()) {
        throw ToolException.ValidationFailure("Git repository URL must identify owner and repository")
    }

    return GitRepositoryUrl(
        provider = provider,
        owner = owner,
        repository = repository,
        sshUrl = "git@$host:$owner/$repository.git",
    )
}

private data class GitRepositoryUrl(
    val provider: GitProvider,
    val owner: String,
    val repository: String,
    val sshUrl: String,
) {
    val canonical = "${provider.configKey}/${owner.lowercase()}/${repository.lowercase()}"
}

private enum class GitProvider(
    val configKey: String,
    val displayName: String,
) {
    GITHUB("github", "GitHub"),
    BITBUCKET("bitbucket", "Bitbucket"),
}
