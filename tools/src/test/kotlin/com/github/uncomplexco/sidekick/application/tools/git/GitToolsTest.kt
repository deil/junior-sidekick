package com.github.uncomplexco.sidekick.application.tools.git

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class GitToolsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `clones github https url into project path using ssh url`() {
        // Arrange
        val git = FakeGitRepository()
        val tools = tools(git)

        // Act
        val result = tools.clone("https://github.com/acme/private.git", "/data/project/private")

        // Assert
        assertEquals("/data/project/private", result.path)
        assertEquals("develop", result.branch)
        assertEquals("abc123", result.commit_hash)
        assertEquals("git@github.com:acme/private.git", git.clonedUrl)
        assertEquals("/keys/github", git.clonedSshKeyFile)
        assertEquals(listOf("develop", "master", "main"), git.clonedPreferredBranches)
    }

    @Test
    fun `fetches existing checkout with matching origin`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout), origins = mapOf(checkout to "git@github.com:acme/repo.git"))
        val tools = tools(git)

        // Act
        val result = tools.clone("https://github.com/acme/repo", "/data/project/repo")

        // Assert
        assertEquals("/data/project/repo", result.path)
        assertEquals("main", result.branch)
        assertEquals("def456", result.commit_hash)
        assertEquals(checkout, git.fetchedCheckout)
    }

    @Test
    fun `rejects destination outside project root`() {
        // Arrange
        val tools = tools(FakeGitRepository())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.clone("git@github.com:acme/repo.git", "/data/project/../global/repo")
        }
    }

    @Test
    fun `rejects existing checkout with different origin`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout), origins = mapOf(checkout to "git@github.com:acme/other.git"))
        val tools = tools(git)

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.clone("git@github.com:acme/repo.git", "/data/project/repo")
        }
    }

    @Test
    fun `clones into existing empty directory`() {
        // Arrange
        Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository()
        val tools = tools(git)

        // Act
        val result = tools.clone("git@bitbucket.org:acme/repo.git", "/data/project/repo")

        // Assert
        assertEquals("/data/project/repo", result.path)
        assertEquals("git@bitbucket.org:acme/repo.git", git.clonedUrl)
        assertEquals("/keys/bitbucket", git.clonedSshKeyFile)
    }

    @Test
    fun `fails at call time when provider key is missing`() {
        // Arrange
        val config = GitToolConfig()
        config.bitbucket.sshKeyFile = "/keys/bitbucket"
        val tools = GitTools(config, virtualPaths(), FakeGitRepository())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.clone("git@github.com:acme/repo.git", "/data/project/repo")
        }
    }

    private fun tools(git: FakeGitRepository): GitTools {
        val config = GitToolConfig()
        config.github.sshKeyFile = "/keys/github"
        config.bitbucket.sshKeyFile = "/keys/bitbucket"
        return GitTools(config, virtualPaths(), git)
    }

    private fun virtualPaths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = Files.createDirectories(dir.resolve("project")),
        )
}

private class FakeGitRepository(
    private val gitRepositories: Set<Path> = emptySet(),
    private val origins: Map<Path, String> = emptyMap(),
) : GitRepository {
    var clonedUrl: String? = null
    var clonedSshKeyFile: String? = null
    var clonedPreferredBranches: List<String>? = null
    var fetchedCheckout: Path? = null

    override fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState {
        clonedUrl = url
        clonedSshKeyFile = sshKeyFile
        clonedPreferredBranches = preferredBranches
        return GitRepositoryState(checkout, "develop", "abc123")
    }

    override fun fetch(
        checkout: Path,
        sshKeyFile: String,
    ): GitRepositoryState {
        fetchedCheckout = checkout
        return GitRepositoryState(checkout, "main", "def456")
    }

    override fun isGitRepository(checkout: Path): Boolean = checkout in gitRepositories

    override fun originUrl(checkout: Path): String? = origins[checkout]
}
