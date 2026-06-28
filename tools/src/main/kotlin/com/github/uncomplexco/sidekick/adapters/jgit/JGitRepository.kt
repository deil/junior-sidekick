package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitRepository
import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryState
import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

class JGitRepository : GitRepository {
    override fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState {
        Git
            .cloneRepository()
            .setURI(url)
            .setDirectory(checkout.toFile())
            .applySsh(sshKeyFile, workingDirectory)
            .call()
            .use { git -> checkoutPreferredBranch(git, preferredBranches) }
        return state(checkout, GitRepositoryStatus.CLONED)
    }

    override fun fetch(
        checkout: Path,
        sshKeyFile: String,
    ): GitRepositoryState {
        Git.open(checkout.toFile()).use { git ->
            git
                .fetch()
                .setRemote("origin")
                .setRemoveDeletedRefs(true)
                .applySsh(sshKeyFile, checkout)
                .call()
            return state(checkout, fastForwardCurrentBranch(git))
        }
    }

    override fun isGitRepository(checkout: Path): Boolean =
        runCatching {
            Git.open(checkout.toFile()).close()
            true
        }.getOrDefault(false)

    override fun originUrl(checkout: Path): String? =
        Git.open(checkout.toFile()).use { git ->
            git.repository.config.getString("remote", "origin", "url")
        }

    private fun checkoutPreferredBranch(
        git: Git,
        preferredBranches: List<String>,
    ) {
        val selectedBranch = preferredBranches.firstOrNull { branch -> git.repository.findRef("refs/remotes/origin/$branch") != null }
            ?: return

        if (git.repository.branch == selectedBranch) {
            return
        }

        git
            .checkout()
            .setCreateBranch(true)
            .setName(selectedBranch)
            .setStartPoint("origin/$selectedBranch")
            .call()
    }

    private fun fastForwardCurrentBranch(git: Git): GitRepositoryStatus {
        val branch = git.repository.branch ?: return GitRepositoryStatus.FETCHED_DETACHED_HEAD
        val head = git.repository.resolve("HEAD") ?: return GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED
        val remote = git.repository.findRef("refs/remotes/origin/$branch")?.objectId ?: return GitRepositoryStatus.FETCHED_NO_REMOTE_BRANCH
        if (head == remote) {
            return GitRepositoryStatus.FETCHED_UP_TO_DATE
        }

        val canFastForward =
            RevWalk(git.repository).use { walk ->
                walk.isMergedInto(walk.parseCommit(head), walk.parseCommit(remote))
            }
        if (!canFastForward) {
            return GitRepositoryStatus.FETCHED_DIVERGED
        }

        return runCatching {
            val result =
                git
                    .merge()
                    .include(remote)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .call()
            when (result.mergeStatus) {
                MergeResult.MergeStatus.FAST_FORWARD -> GitRepositoryStatus.FETCHED_FAST_FORWARDED
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> GitRepositoryStatus.FETCHED_UP_TO_DATE
                else -> GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED
            }
        }.getOrDefault(GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED)
    }

    private fun state(
        checkout: Path,
        status: GitRepositoryStatus,
    ): GitRepositoryState =
        Git.open(checkout.toFile()).use { git ->
            GitRepositoryState(
                path = checkout,
                branch = git.repository.currentBranch(),
                commitHash = git.repository.resolve("HEAD").name,
                status = status,
            )
        }
}

private fun Repository.currentBranch(): String = branch ?: "HEAD"

private fun org.eclipse.jgit.api.CloneCommand.applySsh(
    sshKeyPath: String,
    workingDirectory: Path,
) = apply {
    setTransportConfigCallback(sshKeyCallback(sshKeyPath, workingDirectory))
}

private fun org.eclipse.jgit.api.FetchCommand.applySsh(
    sshKeyPath: String,
    workingDirectory: Path,
) = apply {
    setTransportConfigCallback(sshKeyCallback(sshKeyPath, workingDirectory))
}

private fun sshKeyCallback(
    sshKeyPath: String,
    workingDirectory: Path,
) =
    SshKeyTransportConfigCallback(
        sshKeyPath = Path.of(sshKeyPath).toAbsolutePath().normalize(),
        sshHomeDirectory = workingDirectory.resolve("tmp").toAbsolutePath().normalize(),
    )

private class SshKeyTransportConfigCallback(
    private val sshKeyPath: Path,
    private val sshHomeDirectory: Path,
) : TransportConfigCallback {
    override fun configure(transport: Transport) {
        if (transport !is SshTransport) {
            return
        }

        Files.createDirectories(sshHomeDirectory)
        transport.sshSessionFactory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(sshHomeDirectory.toFile())
                .setSshDirectory(sshHomeDirectory.toFile())
                .setDefaultIdentities { listOf(sshKeyPath) }
                .setServerKeyDatabase { _, _ -> TrustingServerKeyDatabase }
                .build(null) as SshdSessionFactory
    }
}

private object TrustingServerKeyDatabase : ServerKeyDatabase {
    override fun lookup(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        config: ServerKeyDatabase.Configuration,
    ): List<PublicKey> = emptyList()

    override fun accept(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        serverKey: PublicKey,
        config: ServerKeyDatabase.Configuration,
        provider: CredentialsProvider?,
    ): Boolean = true
}
