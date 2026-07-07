package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.adapters.git.gitRepositoryCheckoutPath
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.markdown.parseMarkdownFrontmatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `loads built in subagent`() {
        // Arrange
        val subagents = Subagents()

        // Act
        val prompt = subagents.catalog().subagents.single { it.name == "general" }.systemPrompt

        // Assert
        assertTrue(prompt.startsWith("You are a general-purpose subagent."), prompt)
    }

    @Test
    fun `lists built in subagents with descriptions`() {
        // Arrange
        val catalog = Subagents()

        // Act
        val subagents = catalog.catalog().subagents

        // Assert
        assertEquals(listOf("general"), subagents.map { it.name })
        assertEquals(listOf("general-purpose agent for complex questions and multi-step tasks"), subagents.map { it.description })
        assertTrue(subagents.single().systemPrompt.startsWith("You are a general-purpose subagent."))
    }

    @Test
    fun `loads subagents from configured extension repositories`() {
        // Arrange
        val config = config()
        val checkout = gitRepositoryCheckoutPath(config.workspaceLayout().extensionsRepositoryDirectoryPath(), "git@github.com:deil/agents.git")
        writeSubagentFile(
            checkout.resolve("sidekick/agents/explore.md"),
            "explore",
            "fast codebase exploration agent",
            "Inspect the codebase and report findings.",
        )
        Files.writeString(
            config.workspaceLayout().extensionsConfigPath(),
            """{"extensions": [{"url": "git@github.com:deil/agents.git", "path": "sidekick"}]}""",
        )
        val catalog = Subagents(config)

        // Act
        val subagents = catalog.catalog().subagents

        // Assert
        assertEquals(listOf("general", "explore"), subagents.map { it.name })
        assertEquals("fast codebase exploration agent", subagents.single { it.name == "explore" }.description)
        assertEquals(
            "Inspect the codebase and report findings.",
            subagents.single { it.name == "explore" }.systemPrompt,
        )
    }

    @Test
    fun `skips invalid extension subagents`() {
        // Arrange
        val config = config()
        val checkout = gitRepositoryCheckoutPath(config.workspaceLayout().extensionsRepositoryDirectoryPath(), "git@github.com:deil/agents.git")
        writeSubagentFile(checkout.resolve("agents/valid.md"), "valid", "Valid agent", "Run valid tasks.")
        writeSubagentFile(checkout.resolve("agents/wrong-file.md"), "wrong-name", "Wrong file", "Skip me.")
        writeSubagentFile(checkout.resolve("agents/unsafe.md"), "../unsafe", "Unsafe", "Skip me.")
        Files.createDirectories(checkout.resolve("agents"))
        Files.writeString(checkout.resolve("agents/missing-description.md"), "---\nname: missing-description\n---\nSkip me.")
        Files.writeString(
            config.workspaceLayout().extensionsConfigPath(),
            """{"extensions": [{"url": "git@github.com:deil/agents.git"}]}""",
        )
        val catalog = Subagents(config)

        // Act
        val subagents = catalog.catalog().subagents

        // Assert
        assertEquals(listOf("general", "valid"), subagents.map { it.name })
    }

    @Test
    fun `strips frontmatter from subagent definition`() {
        // Arrange
        val markdown =
            """
            ---
            name: general
            description: General agent
            ---
            System prompt.
            """.trimIndent()

        // Act
        val prompt =
            parseMarkdownFrontmatter(markdown).body

        // Assert
        assertEquals("System prompt.", prompt)
    }

    private fun writeSubagentFile(
        subagentFile: Path,
        name: String,
        description: String,
        prompt: String,
    ) {
        Files.createDirectories(subagentFile.parent)
        Files.writeString(
            subagentFile,
            """
            ---
            name: $name
            description: $description
            ---
            $prompt
            """.trimIndent(),
        )
    }

    private fun config(): AgentConfig = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
}
