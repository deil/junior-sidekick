package com.github.uncomplexco.sidekick.application.agent.workspace.global

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalWorkspaceTest {
    @TempDir
    lateinit var dir: Path

    private val globalWorkspace = GlobalWorkspace()

    @Test
    fun `loads knowledge repository config from workspace config directory`() {
        Files.writeString(
            config().workspaceLayout().knowledgeConfigPath(),
            """
            {"knowledge": [{"url": "git@github.com:deil/global.git", "path": "docs", "sshKeyPath": "/home/sidekick/.ssh/global"}]}
            """.trimIndent(),
        )

        val config = globalWorkspace.loadConfig(config())

        assertEquals(
            listOf(GlobalWorkspaceRepository("git@github.com:deil/global.git", "docs", "/home/sidekick/.ssh/global")),
            config.knowledge,
        )
    }

    @Test
    fun `returns empty config when knowledge config file is missing`() {
        val config = globalWorkspace.loadConfig(config())

        assertEquals(emptyList(), config.knowledge)
    }

    @Test
    fun `does nothing when knowledge config file is missing`() {
        val checkouts = globalWorkspace.sync(config())

        assertEquals(emptyList(), checkouts)
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/knowledge")))
    }

    @Test
    fun `does nothing when knowledge config is empty`() {
        Files.writeString(config().workspaceLayout().knowledgeConfigPath(), """{"knowledge": []}""")

        val checkouts = globalWorkspace.sync(config())

        assertEquals(emptyList(), checkouts)
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/knowledge")))
    }

    @Test
    fun `checkout path is stable and unique per repository url`() {
        val repo = GlobalWorkspaceRepository("git@github.com:deil/global.git", "docs")

        val first = globalWorkspace.checkoutPath(config(), repo)
        val second = globalWorkspace.checkoutPath(config(), repo)
        val other = globalWorkspace.checkoutPath(config(), GlobalWorkspaceRepository("git@github.com:deil/other-global.git", "docs"))

        assertEquals(first, second)
        assertTrue(first.startsWith(dir.resolve("workspace/data/repositories/knowledge")))
        assertTrue(first.fileName.toString().startsWith("global-"))
        assertTrue(other.fileName.toString().startsWith("other-global-"))
        assertTrue(first != other)
    }

    @Test
    fun `syncs local git repository into global workspace`() {
        val source = Files.createDirectories(dir.resolve("source"))
        Files.writeString(source.resolve("handbook.md"), "Version 1\n")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add handbook").call()
        }
        Files.writeString(
            config().workspaceLayout().knowledgeConfigPath(),
            """
            {"knowledge": [{"url": "${source.toUri()}"}]}
            """.trimIndent(),
        )

        val checkouts = globalWorkspace.sync(config())

        assertEquals(1, checkouts.size)
        assertEquals("Version 1\n", Files.readString(checkouts.single().resolve("handbook.md")))
    }

    @Test
    fun `refreshes existing global repository checkout`() {
        val source = Files.createDirectories(dir.resolve("source"))
        Files.writeString(source.resolve("handbook.md"), "Version 1\n")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add handbook").call()
        }
        Files.writeString(
            config().workspaceLayout().knowledgeConfigPath(),
            """
            {"knowledge": [{"url": "${source.toUri()}"}]}
            """.trimIndent(),
        )
        val checkout = globalWorkspace.sync(config()).single()

        Git.open(source.toFile()).use { git ->
            Files.writeString(source.resolve("handbook.md"), "Version 2\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("update handbook").call()
        }

        globalWorkspace.sync(config())

        assertEquals("Version 2\n", Files.readString(checkout.resolve("handbook.md")))
    }

    private fun config(): AgentConfig = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
}
