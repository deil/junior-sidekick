package com.github.uncomplexco.sidekick.adapters.git

import com.github.uncomplexco.sidekick.application.utils.sha256
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
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

fun gitRepositoryCheckoutPath(
    root: Path,
    url: String,
): Path {
    val repositoryName =
        url
            .substringAfterLast('/')
            .substringAfterLast(':')
            .removeSuffix(".git")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "repository" }
    return root.resolve("$repositoryName-${sha256(url).take(12)}")
}

fun syncGitRepository(
    url: String,
    sshKeyPath: String?,
    checkout: Path,
    workingDirectory: Path,
    checkoutDescription: String,
) {
    Files.createDirectories(checkout.parent)
    if (!Files.exists(checkout)) {
        Git
            .cloneRepository()
            .setURI(url)
            .setDirectory(checkout.toFile())
            .applySsh(sshKeyPath, workingDirectory)
            .call()
            .close()
        return
    }

    require(Files.isDirectory(checkout.resolve(".git"))) {
        "$checkoutDescription checkout exists but is not a Git repository: $checkout"
    }

    Git.open(checkout.toFile()).use { git ->
        git
            .fetch()
            .setRemote("origin")
            .setRemoveDeletedRefs(true)
            .applySsh(sshKeyPath, workingDirectory)
            .call()
        git
            .reset()
            .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
            .setRef(defaultRemoteBranch(git))
            .call()
    }
}

private fun defaultRemoteBranch(git: Git): String = Constants.R_REMOTES + "origin/" + git.repository.branchName()

private fun Repository.branchName(): String = branch

private fun org.eclipse.jgit.api.CloneCommand.applySsh(
    sshKeyPath: String?,
    workingDirectory: Path,
) = apply {
    sshKeyPath?.let { setTransportConfigCallback(sshKeyCallback(it, workingDirectory)) }
}

private fun org.eclipse.jgit.api.FetchCommand.applySsh(
    sshKeyPath: String?,
    workingDirectory: Path,
) = apply {
    sshKeyPath?.let { setTransportConfigCallback(sshKeyCallback(it, workingDirectory)) }
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
        Files.createDirectories(sshHomeDirectory)
        (transport as SshTransport).sshSessionFactory =
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
