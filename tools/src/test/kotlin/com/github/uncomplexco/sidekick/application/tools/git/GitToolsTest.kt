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
        assertEquals("cloned", result.status)
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
        assertEquals("fetched_fast_forwarded", result.status)
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

    @Test
    fun `pushes repository current branch upstream using provider key`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout))
        val tools = tools(git)

        // Act
        val result = tools.push("/data/project/repo")

        // Assert
        assertEquals("/data/project/repo", result.path)
        assertEquals("main", result.branch)
        assertEquals("ghi789", result.commit_hash)
        assertEquals("origin", result.remote)
        assertEquals("refs/heads/main", result.upstream)
        assertEquals(true, result.dirty)
        assertEquals("pushed", result.status)
        assertEquals("refs/heads/main: ok", result.message)
        assertEquals(checkout, git.pushedCheckout)
        assertEquals("/keys/github", git.pushedSshKeyFile)
        assertEquals(false, git.pushedTags)
    }

    @Test
    fun `pushes tags when tags option is true`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout))
        val tools = tools(git)

        // Act
        tools.push("/data/project/repo", tags = true)

        // Assert
        assertEquals(true, git.pushedTags)
    }

    @Test
    fun `push returns no upstream status without attempting push`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                pushPlan = { path ->
                    GitPushPlan(
                        path = path,
                        branch = "main",
                        commitHash = "ghi789",
                        dirty = false,
                        remote = null,
                        upstream = null,
                        remoteUrl = null,
                        status = GitPushStatus.NO_UPSTREAM,
                        message = "Current branch has no configured upstream",
                    )
                },
            )
        val tools = tools(git)

        // Act
        val result = tools.push("/data/project/repo")

        // Assert
        assertEquals("no_upstream", result.status)
        assertEquals("Current branch has no configured upstream", result.message)
        assertEquals(null, git.pushedCheckout)
    }

    @Test
    fun `push rejects unsupported upstream remote provider`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                pushPlan = { path ->
                    GitPushPlan(
                        path = path,
                        branch = "main",
                        commitHash = "ghi789",
                        dirty = false,
                        remote = "origin",
                        upstream = "refs/heads/main",
                        remoteUrl = "ssh://git@example.com/acme/repo.git",
                        status = null,
                        message = "Ready",
                    )
                },
            )
        val tools = tools(git)

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.push("/data/project/repo")
        }
    }

    @Test
    fun `push rejects non repository path`() {
        // Arrange
        Files.createDirectories(dir.resolve("project/repo"))
        val tools = tools(FakeGitRepository())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.push("/data/project/repo")
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
    private val pushPlan: ((Path) -> GitPushPlan)? = null,
) : GitRepository {
    var clonedUrl: String? = null
    var clonedSshKeyFile: String? = null
    var clonedPreferredBranches: List<String>? = null
    var fetchedCheckout: Path? = null
    var pushedCheckout: Path? = null
    var pushedSshKeyFile: String? = null
    var pushedTags: Boolean? = null

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
        return GitRepositoryState(checkout, "develop", "abc123", GitRepositoryStatus.CLONED)
    }

    override fun fetch(
        checkout: Path,
        sshKeyFile: String,
    ): GitRepositoryState {
        fetchedCheckout = checkout
        return GitRepositoryState(checkout, "main", "def456", GitRepositoryStatus.FETCHED_FAST_FORWARDED)
    }

    override fun isGitRepository(checkout: Path): Boolean = checkout in gitRepositories

    override fun originUrl(checkout: Path): String? = origins[checkout]

    override fun pushPlan(checkout: Path): GitPushPlan =
        pushPlan?.invoke(checkout) ?: GitPushPlan(
            path = checkout,
            branch = "main",
            commitHash = "ghi789",
            dirty = true,
            remote = "origin",
            upstream = "refs/heads/main",
            remoteUrl = "git@github.com:acme/repo.git",
            status = null,
            message = "Ready",
        )

    override fun push(
        checkout: Path,
        sshKeyFile: String,
        tags: Boolean,
    ): GitPushState {
        pushedCheckout = checkout
        pushedSshKeyFile = sshKeyFile
        pushedTags = tags
        return GitPushState(
            path = checkout,
            branch = "main",
            commitHash = "ghi789",
            dirty = true,
            remote = "origin",
            upstream = "refs/heads/main",
            status = GitPushStatus.PUSHED,
            message = "refs/heads/main: ok",
        )
    }
}
