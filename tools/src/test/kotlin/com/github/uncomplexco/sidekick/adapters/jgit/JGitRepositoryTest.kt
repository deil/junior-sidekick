package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryStatus
import com.github.uncomplexco.sidekick.application.tools.git.GitPushStatus
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

    @Test
    fun `push sends current branch without tags by default`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        commit(checkout, "two")
        tag(checkout, "v1")
        val expectedHead = head(checkout)

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = null, tags = false)

        // Assert
        assertEquals(GitPushStatus.PUSHED, result.status)
        assertEquals(expectedHead, result.commitHash)
        assertEquals(expectedHead, head(remote))
        assertEquals(false, hasTag(remote, "v1"))
    }

    @Test
    fun `push sends all tags when tags option is true`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        tag(checkout, "v1")

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = null, tags = true)

        // Assert
        assertEquals(GitPushStatus.PUSHED, result.status)
        assertEquals(true, hasTag(remote, "v1"))
    }

    @Test
    fun `push reports up to date when branch and tags are already pushed`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = null, tags = false)

        // Assert
        assertEquals(GitPushStatus.UP_TO_DATE, result.status)
    }

    @Test
    fun `push reports non-fast-forward rejection`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        val other = dir.resolve("other")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(other.toFile()).call().close()
        commit(other, "remote-two")
        JGitRepository().push(other, sshKeyFile = "ignored", branch = null, tags = false)
        commit(checkout, "local-two")

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = null, tags = false)

        // Assert
        assertEquals(GitPushStatus.REJECTED_NON_FAST_FORWARD, result.status)
        assertEquals(head(checkout), result.commitHash)
    }

    @Test
    fun `push plan reports dirty working tree without blocking`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        Files.writeString(checkout.resolve("dirty.txt"), "dirty\n")

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = null, tags = false)

        // Assert
        assertEquals(GitPushStatus.UP_TO_DATE, result.status)
        assertEquals(true, result.dirty)
    }

    @Test
    fun `push sends selected branch to same named remote branch`() {
        // Arrange
        val remote = createBareRemote()
        val checkout = dir.resolve("checkout")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(checkout.toFile()).call().close()
        val originalBranch = currentBranch(checkout)
        Git.open(checkout.toFile()).use { git ->
            git.checkout().setCreateBranch(true).setName("release").call()
        }
        commit(checkout, "release")
        val expectedHead = head(checkout)
        Git.open(checkout.toFile()).use { git ->
            git.checkout().setName(originalBranch).call()
        }

        // Act
        val result = JGitRepository().push(checkout, sshKeyFile = "ignored", branch = "release", tags = false)

        // Assert
        assertEquals(GitPushStatus.PUSHED, result.status)
        assertEquals("release", result.branch)
        assertEquals(expectedHead, result.commitHash)
        assertEquals(expectedHead, refHead(remote, "refs/heads/release"))
    }

    private fun createRepository(name: String): Path {
        val repository = Files.createDirectories(dir.resolve(name))
        Git.init().setDirectory(repository.toFile()).call().close()
        return repository
    }

    private fun createBareRemote(): Path {
        val seed = createRepository("seed-${System.nanoTime()}")
        commit(seed, "one")
        val remote = dir.resolve("remote-${System.nanoTime()}.git")
        Git.cloneRepository().setURI(seed.toUri().toString()).setDirectory(remote.toFile()).setBare(true).call().close()
        return remote
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

    private fun tag(
        repository: Path,
        name: String,
    ) {
        Git.open(repository.toFile()).use { git ->
            git.tag().setName(name).call()
        }
    }

    private fun hasTag(
        repository: Path,
        name: String,
    ): Boolean =
        Git.open(repository.toFile()).use { git ->
            git.repository.findRef("refs/tags/$name") != null
        }

    private fun head(repository: Path): String =
        Git.open(repository.toFile()).use { git ->
            git.repository.resolve("HEAD").name
        }

    private fun refHead(
        repository: Path,
        ref: String,
    ): String =
        Git.open(repository.toFile()).use { git ->
            git.repository.resolve(ref).name
        }

    private fun currentBranch(repository: Path): String =
        Git.open(repository.toFile()).use { git ->
            git.repository.currentBranch()
        }
}
