package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryStatus
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class JGitRepositoryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `fetch fast-forwards current branch when remote is ahead`() {
        // Arrange
        val remote = createRepository("remote")
        commit(remote, "one")
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        commit(remote, "two")
        val expectedHead = head(remote)

        // Act
        val result = JGitRepository().fetch(checkout, sshKeyFile = "ignored")

        // Assert
        assertEquals(GitRepositoryStatus.FETCHED_FAST_FORWARDED, result.status)
        assertEquals(expectedHead, result.commitHash)
        assertEquals(expectedHead, head(checkout))
    }

    @Test
    fun `fetch reports non-fast-forward when local branch diverged`() {
        // Arrange
        val remote = createRepository("remote")
        commit(remote, "one")
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        val originalHead = head(checkout)
        commit(remote, "remote-two")
        commit(checkout, "local-two")

        // Act
        val result = JGitRepository().fetch(checkout, sshKeyFile = "ignored")

        // Assert
        assertEquals(GitRepositoryStatus.FETCHED_DIVERGED, result.status)
        assertEquals(head(checkout), result.commitHash)
        assertEquals(false, result.commitHash == originalHead)
    }

    private fun createRepository(name: String): Path {
        val repository = Files.createDirectories(dir.resolve(name))
        Git.init().setDirectory(repository.toFile()).call().close()
        return repository
    }

    private fun commit(
        repository: Path,
        text: String,
    ) {
        Git.open(repository.toFile()).use { git ->
            Files.writeString(repository.resolve("file.txt"), "$text\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage(text).call()
        }
    }

    private fun head(repository: Path): String =
        Git.open(repository.toFile()).use { git ->
            git.repository.resolve("HEAD").name
        }
}
