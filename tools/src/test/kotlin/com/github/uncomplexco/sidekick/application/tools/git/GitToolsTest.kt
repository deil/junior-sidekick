package com.github.uncomplexco.sidekick.application.tools.git

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.adapters.jgit.GitPushPlan
import com.github.uncomplexco.sidekick.adapters.jgit.GitPushStatus
import com.github.uncomplexco.sidekick.adapters.jgit.GitRepository
import com.github.uncomplexco.sidekick.adapters.jgit.LocalGitCredentials
import com.github.uncomplexco.sidekick.adapters.jgit.LocalWorkspaceGit
import com.github.uncomplexco.sidekick.adapters.spring.GitToolConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.testing.FakeGitRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class GitToolsTest {
    @TempDir lateinit var dir: Path

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
        assertEquals("abc123", result.commitHash)
        assertEquals("cloned", result.status)
        assertEquals(
            "abc123",
            Json.parseToJsonElement(Json.encodeToString(result))
                .jsonObject["commit_hash"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("git@github.com:acme/private.git", git.clonedUrl)
        assertEquals("/keys/github", git.clonedSshKeyFile)
        assertEquals(listOf("develop", "master", "main"), git.clonedPreferredBranches)
    }

    @Test
    fun `clones into work path using ssh url`() {
        // Arrange
        val git = FakeGitRepository()
        val tools = tools(git)

        // Act
        val result = tools.clone("https://github.com/acme/private.git", "/work/private")

        // Assert
        assertEquals("/work/private", result.path)
        assertEquals(dir.resolve("work/private").toAbsolutePath().normalize(), git.clonedCheckout)
    }

    @Test
    fun `fetches existing checkout with matching origin`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                origins = mapOf(checkout to "git@github.com:acme/repo.git"),
            )
        val tools = tools(git)

        // Act
        val result = tools.clone("https://github.com/acme/repo", "/data/project/repo")

        // Assert
        assertEquals("/data/project/repo", result.path)
        assertEquals("main", result.branch)
        assertEquals("def456", result.commitHash)
        assertEquals("fetched_fast_forwarded", result.status)
        assertEquals(checkout, git.fetchedCheckout)
    }

    @Test
    fun `converts matching Azure DevOps https origin before fetch`() {
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                origins =
                    mapOf(
                        checkout to
                            "https://Profitsword@dev.azure.com/Profitsword/Katana/_git/Actabl.BI.Gateway"
                    ),
            )
        val tools = tools(git)

        tools.clone(
            "https://dev.azure.com/Profitsword/Katana/_git/Actabl.BI.Gateway",
            "/data/project/repo",
        )

        assertEquals(
            Triple(
                checkout,
                "origin",
                "git@ssh.dev.azure.com:v3/Profitsword/Katana/Actabl.BI.Gateway",
            ),
            git.updatedRemote,
        )
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
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                origins = mapOf(checkout to "git@github.com:acme/other.git"),
            )
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
    fun `clones gitlab nested namespace using fallback key`() {
        // Arrange
        val git = FakeGitRepository()
        val tools = tools(git)

        // Act
        tools.clone("https://gitlab.com/acme/platform/repo.git", "/data/project/repo")

        // Assert
        assertEquals("git@gitlab.com:acme/platform/repo.git", git.clonedUrl)
        assertEquals("/keys/other", git.clonedSshKeyFile)
    }

    @Test
    fun `clones Azure DevOps https url using provider ssh url`() {
        val git = FakeGitRepository()
        val tools = tools(git)

        tools.clone(
            "https://Profitsword@dev.azure.com/Profitsword/Katana/_git/Actabl.BI.Gateway",
            "/data/project/gateway",
        )

        assertEquals(
            "git@ssh.dev.azure.com:v3/Profitsword/Katana/Actabl.BI.Gateway",
            git.clonedUrl,
        )
        assertEquals("/keys/other", git.clonedSshKeyFile)
    }

    @Test
    fun `accepts Azure DevOps ssh url`() {
        val git = FakeGitRepository()
        val tools = tools(git)

        tools.clone(
            "git@ssh.dev.azure.com:v3/Profitsword/Katana/Actabl.BI.Gateway",
            "/data/project/gateway",
        )

        assertEquals(
            "git@ssh.dev.azure.com:v3/Profitsword/Katana/Actabl.BI.Gateway",
            git.clonedUrl,
        )
    }

    @Test
    fun `rejects existing fallback checkout from a different host`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                origins = mapOf(checkout to "git@example.com:acme/repo.git"),
            )
        val tools = tools(git)

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.clone("git@gitlab.com:acme/repo.git", "/data/project/repo")
        }
    }

    @Test
    fun `fails at call time when provider key is missing`() {
        // Arrange
        val config = GitToolConfig()
        config.bitbucket.sshKeyFile = "/keys/bitbucket"
        val tools = tools(config, FakeGitRepository())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.clone("git@github.com:acme/repo.git", "/data/project/repo")
        }
    }

    @Test
    fun `fails at call time when fallback key is missing`() {
        // Arrange
        val tools = tools(GitToolConfig(), FakeGitRepository())

        // Act
        val error =
            assertThrows<ToolException.ValidationFailure> {
                tools.clone("git@gitlab.com:acme/repo.git", "/data/project/repo")
            }

        // Assert
        assertEquals(
            "Git provider SSH key is not configured: agent.tools.git.ssh-key-file",
            error.message,
        )
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
        assertEquals("ghi789", result.commitHash)
        assertEquals("origin", result.remote)
        assertEquals("refs/heads/main", result.upstream)
        assertEquals(true, result.dirty)
        assertEquals("pushed", result.status)
        assertEquals("refs/heads/main: ok", result.message)
        assertEquals(checkout, git.pushedCheckout)
        assertEquals("/keys/github", git.pushedSshKeyFile)
        assertEquals(null, git.pushedBranch)
        assertEquals(false, git.pushedAll)
        assertEquals(false, git.pushedTags)
    }

    @Test
    fun `converts Azure DevOps https upstream before push`() {
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                pushPlan = { path, _ ->
                    GitPushPlan(
                        path = path,
                        branch = "main",
                        commitHash = "ghi789",
                        dirty = false,
                        remote = "origin",
                        upstream = "refs/heads/main",
                        remoteUrl =
                            "https://dev.azure.com/Profitsword/Katana/_git/Actabl.BI.Gateway",
                        status = null,
                        message = "Ready",
                    )
                },
            )
        val tools = tools(git)

        tools.push("/data/project/repo")

        assertEquals(
            Triple(
                checkout,
                "origin",
                "git@ssh.dev.azure.com:v3/Profitsword/Katana/Actabl.BI.Gateway",
            ),
            git.updatedRemote,
        )
    }

    @Test
    fun `pushes selected branch using provider key`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout))
        val tools = tools(git)

        // Act
        val result = tools.push("/data/project/repo", refspec = "release")

        // Assert
        assertEquals("release", result.branch)
        assertEquals("refs/heads/release", result.upstream)
        assertEquals("release", git.pushedBranch)
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
    fun `pushes all branches when all option is true`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout))
        val tools = tools(git)

        // Act
        tools.push("/data/project/repo", all = true)

        // Assert
        assertEquals(true, git.pushedAll)
        assertEquals(null, git.pushedBranch)
    }

    @Test
    fun `push rejects all with refspec`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git = FakeGitRepository(gitRepositories = setOf(checkout))
        val tools = tools(git)

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.push("/data/project/repo", refspec = "release", all = true)
        }
    }

    @Test
    fun `push returns no upstream status without attempting push`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                pushPlan = { path, _ ->
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
    fun `pushes other upstream provider using fallback key`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                pushPlan = { path, _ ->
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

        // Act
        tools.push("/data/project/repo")

        // Assert
        assertEquals("/keys/other", git.pushedSshKeyFile)
    }

    @Test
    fun `push rejects non repository path`() {
        // Arrange
        Files.createDirectories(dir.resolve("project/repo"))
        val tools = tools(FakeGitRepository())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> { tools.push("/data/project/repo") }
    }

    @Test
    fun `pulls remote refspec using provider key`() {
        // Arrange
        val checkout = Files.createDirectories(dir.resolve("project/repo"))
        val git =
            FakeGitRepository(
                gitRepositories = setOf(checkout),
                origins = mapOf(checkout to "git@github.com:acme/repo.git"),
            )
        val tools = tools(git)

        // Act
        val result = tools.pull("/data/project/repo", remote = "origin", refspec = "main")

        // Assert
        assertEquals("/data/project/repo", result.path)
        assertEquals("main", result.branch)
        assertEquals("jkl012", result.commitHash)
        assertEquals("origin", result.remote)
        assertEquals("main", result.upstream)
        assertEquals("fast_forwarded", result.status)
        assertEquals(checkout, git.pulledCheckout)
        assertEquals("/keys/github", git.pulledSshKeyFile)
        assertEquals("origin", git.pulledRemote)
        assertEquals("main", git.pulledRefspec)
    }

    private fun tools(git: FakeGitRepository): GitTools {
        val config = GitToolConfig()
        config.github.sshKeyFile = "/keys/github"
        config.bitbucket.sshKeyFile = "/keys/bitbucket"
        config.sshKeyFile = "/keys/other"
        return tools(config, git)
    }

    private fun tools(config: GitToolConfig, git: GitRepository): GitTools =
        GitTools(
            "C123",
            LocalWorkspaceGit(
                LocalGitCredentials(
                    config.sshKeyFile,
                    config.github.sshKeyFile,
                    config.bitbucket.sshKeyFile,
                ),
                virtualPaths(),
                git,
            ),
        )

    private fun virtualPaths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = Files.createDirectories(dir.resolve("project")),
        )
}
