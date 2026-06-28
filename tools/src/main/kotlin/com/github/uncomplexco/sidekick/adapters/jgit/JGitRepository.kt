package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitRepository
import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryState
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
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

class JGitRepository : GitRepository {
    override fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState {
        val selectedBranch = selectBranch(url, sshKeyFile, workingDirectory, preferredBranches)
        val command =
            Git
                .cloneRepository()
                .setURI(url)
                .setDirectory(checkout.toFile())
                .applySsh(sshKeyFile, workingDirectory)
        selectedBranch?.also { branch ->
            command
                .setBranchesToClone(listOf("refs/heads/$branch"))
                .setBranch("refs/heads/$branch")
        }
        command.call().close()
        return state(checkout)
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
        }
        return state(checkout)
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

    private fun selectBranch(
        url: String,
        sshKeyFile: String,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): String? {
        val remoteBranches =
            Git
                .lsRemoteRepository()
                .setRemote(url)
                .setHeads(true)
                .applySsh(sshKeyFile, workingDirectory)
                .call()
                .map { it.name.removePrefix("refs/heads/") }
                .toSet()
        return preferredBranches.firstOrNull { it in remoteBranches }
    }

    private fun state(checkout: Path): GitRepositoryState =
        Git.open(checkout.toFile()).use { git ->
            GitRepositoryState(
                path = checkout,
                branch = git.repository.currentBranch(),
                commitHash = git.repository.resolve("HEAD").name,
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

private fun org.eclipse.jgit.api.LsRemoteCommand.applySsh(
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
