package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitPushPlan
import com.github.uncomplexco.sidekick.application.tools.git.GitPushStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

internal fun Git.pushPlan(
    checkout: Path,
    branch: String?,
): GitPushPlan {
    val currentBranch = repository.currentBranch()
    val selectedBranch = branch ?: currentBranch
    val branchRef = repository.findRef("refs/heads/$selectedBranch")
    if (branch != null && branchRef == null) {
        throw IllegalArgumentException("Local branch does not exist: $selectedBranch")
    }

    val commitHash = branchRef?.objectId?.name ?: repository.resolve(JGitRepository.HEAD)?.name ?: ""
    val dirty = !status().call().isClean
    val fullBranch = repository.fullBranch
    if (branch == null && (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS))) {
        return GitPushPlan(
            path = checkout,
            branch = selectedBranch,
            commitHash = commitHash,
            dirty = dirty,
            remote = null,
            upstream = null,
            remoteUrl = null,
            status = GitPushStatus.DETACHED_HEAD,
            message = "Cannot push detached HEAD",
        )
    }

    val remote =
        repository.config.getString("branch", selectedBranch, "remote")
            ?: JGitRepository.REMOTE_ORIGIN.takeIf { branch != null }
    val upstream =
        if (branch == null) {
            repository.config.getString("branch", selectedBranch, "merge")
        } else {
            "refs/heads/$selectedBranch"
        }
    if (remote.isNullOrBlank() || upstream.isNullOrBlank()) {
        return GitPushPlan(
            path = checkout,
            branch = selectedBranch,
            commitHash = commitHash,
            dirty = dirty,
            remote = remote,
            upstream = upstream,
            remoteUrl = null,
            status = GitPushStatus.NO_UPSTREAM,
            message = "Current branch has no configured upstream",
        )
    }

    val remoteUrl = repository.config.getString("remote", remote, "url")
    if (remoteUrl.isNullOrBlank()) {
        return GitPushPlan(
            path = checkout,
            branch = selectedBranch,
            commitHash = commitHash,
            dirty = dirty,
            remote = remote,
            upstream = upstream,
            remoteUrl = null,
            status = GitPushStatus.NO_REMOTE,
            message = "Configured upstream remote has no URL: $remote",
        )
    }

    return GitPushPlan(
        path = checkout,
        branch = selectedBranch,
        commitHash = commitHash,
        dirty = dirty,
        remote = remote,
        upstream = upstream,
        remoteUrl = remoteUrl,
        status = null,
        message = "Ready to push $selectedBranch to $remote/$upstream",
    )
}

internal fun Repository.currentBranch(): String {
    val fullBranch = fullBranch ?: return JGitRepository.HEAD
    if (!fullBranch.startsWith(Constants.R_HEADS)) {
        return JGitRepository.HEAD
    }
    return Repository.shortenRefName(fullBranch)
}

internal fun RemoteRefUpdate.Status.toPushStatus(): GitPushStatus =
    when (this) {
        RemoteRefUpdate.Status.OK -> GitPushStatus.PUSHED
        RemoteRefUpdate.Status.UP_TO_DATE -> GitPushStatus.UP_TO_DATE
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> GitPushStatus.REJECTED_NON_FAST_FORWARD
        RemoteRefUpdate.Status.REJECTED_NODELETE -> GitPushStatus.REJECTED_OTHER_REASON
        RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> GitPushStatus.REJECTED_REMOTE_CHANGED
        RemoteRefUpdate.Status.REJECTED_OTHER_REASON -> GitPushStatus.REJECTED_OTHER_REASON
        RemoteRefUpdate.Status.NON_EXISTING -> GitPushStatus.NON_EXISTING_REMOTE_REF
        RemoteRefUpdate.Status.AWAITING_REPORT -> GitPushStatus.AWAITING_REPORT
        RemoteRefUpdate.Status.NOT_ATTEMPTED -> GitPushStatus.NOT_ATTEMPTED
    }

internal fun List<GitPushStatus>.worstOrNull(): GitPushStatus? = maxByOrNull { it.priority }

private val GitPushStatus.priority: Int
    get() =
        when (this) {
            GitPushStatus.UP_TO_DATE -> 0

            GitPushStatus.PUSHED -> 1

            GitPushStatus.REJECTED_NON_FAST_FORWARD,
            GitPushStatus.REJECTED_REMOTE_CHANGED,
            GitPushStatus.REJECTED_OTHER_REASON,
            GitPushStatus.NON_EXISTING_REMOTE_REF,
            GitPushStatus.AWAITING_REPORT,
            GitPushStatus.NOT_ATTEMPTED,
            GitPushStatus.FAILED,
            GitPushStatus.DETACHED_HEAD,
            GitPushStatus.NO_UPSTREAM,
            GitPushStatus.NO_REMOTE,
            -> 2
        }

internal fun Collection<RemoteRefUpdate>.toPushMessage(): String {
    if (isEmpty()) {
        return "No refs reported by remote"
    }
    return joinToString("; ") { update ->
        val reason = update.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
        "${update.remoteName}: ${update.status.name.lowercase()}$reason"
    }
}

internal fun <C, T> C.applySsh(
    sshKeyPath: String,
    workingDirectory: Path,
) where C : TransportCommand<C, T> =
    apply {
        setTransportConfigCallback(sshKeyCallback(sshKeyPath, workingDirectory))
    }

private fun sshKeyCallback(
    sshKeyPath: String,
    workingDirectory: Path,
) = SshKeyTransportConfigCallback(
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
