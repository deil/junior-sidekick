package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.git.GitCloneResult
import com.github.uncomplexco.sidekick.ports.git.GitPullResult
import com.github.uncomplexco.sidekick.ports.git.GitPushResult
import com.github.uncomplexco.sidekick.ports.git.WorkspaceGit
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class LocalWorkspaceGit(
    private val credentials: LocalGitCredentials,
    private val virtualPaths: VirtualPaths,
    private val git: GitRepository = JGitRepository(),
) : WorkspaceGit {
    private val logger = LoggerFactory.getLogger(Loggers.TOOLS.name + ".git")
    private val paths = LocalGitPaths(virtualPaths)
    private val urls = GitRepositoryUrls()

    override suspend fun clone(projectId: String, url: String, path: String): GitCloneResult =
        withContext(Dispatchers.IO) {
            val repository = urls.parse(url)
            val sshKeyFile = credentials.sshKeyFile(repository.provider)
            val checkout = paths.writablePath(path)

            val state =
                if (Files.exists(checkout, NOFOLLOW_LINKS)) {
                    cloneOrFetchExisting(repository, sshKeyFile, checkout)
                } else {
                    paths.prepareDestination(checkout)
                    git.clone(
                        repository.sshUrl,
                        sshKeyFile,
                        checkout,
                        virtualPaths.projectRoot,
                        PREFERRED_BRANCHES,
                    )
                }

            logCloneOutcome(state)

            state.toResult(virtualPaths)
        }

    override suspend fun push(
        projectId: String,
        path: String,
        refspec: String?,
        all: Boolean,
        tags: Boolean,
    ): GitPushResult =
        withContext(Dispatchers.IO) {
            val checkout = paths.projectPath(path)
            require(!Files.isSymbolicLink(checkout)) { "'path' must not be a symbolic link" }
            require(git.isGitRepository(checkout)) { "'path' is not a Git repository" }

            val plan = git.pushPlan(checkout, refspec)
            if (plan.status != null) {
                val state = plan.toState(plan.status, plan.message)
                logPushOutcome(state)
                return@withContext state.toResult(virtualPaths)
            }

            val remoteUrl =
                plan.remoteUrl
                    ?: throw IllegalArgumentException("Git repository upstream remote has no URL")
            val repository = urls.parse(remoteUrl)
            val sshKeyFile = credentials.sshKeyFile(repository.provider)
            val remote =
                plan.remote
                    ?: throw IllegalArgumentException("Git repository has no upstream remote")

            useSshRemote(checkout, remote, remoteUrl, repository)
            val state = git.push(checkout, sshKeyFile, refspec, all, tags)
            logPushOutcome(state)

            state.toResult(virtualPaths)
        }

    override suspend fun pull(
        projectId: String,
        path: String,
        remote: String,
        refspec: String,
    ): GitPullResult =
        withContext(Dispatchers.IO) {
            val checkout = paths.projectPath(path)
            require(!Files.isSymbolicLink(checkout)) { "'path' must not be a symbolic link" }
            require(git.isGitRepository(checkout)) { "'path' is not a Git repository" }

            val remoteUrl =
                git.remoteUrl(checkout, remote)
                    ?: throw IllegalArgumentException("Git repository remote has no URL")
            val repository = urls.parse(remoteUrl)
            val sshKeyFile = credentials.sshKeyFile(repository.provider)

            useSshRemote(checkout, remote, remoteUrl, repository)
            val state = git.pull(checkout, sshKeyFile, remote, refspec)
            logPullOutcome(state)

            state.toResult(virtualPaths)
        }

    private fun cloneOrFetchExisting(
        repository: GitRepositoryUrl,
        sshKeyFile: String,
        checkout: Path,
    ): GitRepositoryState {
        require(!Files.isSymbolicLink(checkout)) {
            "Path must not be a symbolic link: ${virtualPaths.virtualPath(checkout.pathString)}"
        }
        if (!git.isGitRepository(checkout)) {
            require(Files.isDirectory(checkout, NOFOLLOW_LINKS) && isEmptyDirectory(checkout)) {
                "Path exists but is not an empty directory or Git repository: " +
                    virtualPaths.virtualPath(checkout.pathString)
            }

            return git.clone(
                repository.sshUrl,
                sshKeyFile,
                checkout,
                virtualPaths.projectRoot,
                PREFERRED_BRANCHES,
            )
        }

        val origin =
            git.originUrl(checkout)
                ?: throw IllegalArgumentException(
                    "Git repository has no origin remote: ${virtualPaths.virtualPath(checkout.pathString)}"
                )
        val originRepository = urls.parse(origin)
        require(originRepository.canonical == repository.canonical) {
            "Git repository origin does not match requested URL: ${virtualPaths.virtualPath(checkout.pathString)}"
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

    private fun isEmptyDirectory(path: Path): Boolean =
        Files.list(path).use { it.findAny().isEmpty }

    private companion object {
        val PREFERRED_BRANCHES = listOf("develop", "master", "main")
    }
}

private fun GitRepositoryState.toResult(paths: VirtualPaths) =
    GitCloneResult(
        paths.virtualPath(path.pathString),
        branch,
        commitHash,
        status.name.lowercase(),
    )

private fun GitPushState.toResult(paths: VirtualPaths) =
    GitPushResult(
        paths.virtualPath(path.pathString),
        branch,
        commitHash,
        remote,
        upstream,
        dirty,
        status.name.lowercase(),
        message,
    )

private fun GitPullState.toResult(paths: VirtualPaths) =
    GitPullResult(
        paths.virtualPath(path.pathString),
        branch,
        commitHash,
        remote,
        upstream,
        status.name.lowercase(),
        message,
    )
