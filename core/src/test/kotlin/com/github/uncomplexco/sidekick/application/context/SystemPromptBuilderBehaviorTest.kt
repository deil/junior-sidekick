package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptBuilderBehaviorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `embeds optional soul and world files after identity`() {
        val workingDir = Files.createDirectories(dir.resolve("workspace"))
        Files.writeString(workingDir.resolve("SOUL.md"), "Soul line 1\nSoul line 2")
        Files.writeString(workingDir.resolve("WORLD.md"), "World line 1\nWorld line 2")

        val prompt = builder(workingDir).buildSystemPrompt("sidekick", conversationId())

        assertTrue(prompt.contains("# Personality"), prompt)
        assertTrue(prompt.contains("Soul line 1\nSoul line 2"), prompt)
        assertTrue(prompt.contains("# World"), prompt)
        assertTrue(prompt.contains("World line 1\nWorld line 2"), prompt)
        assertTrue(prompt.indexOf("<identity>") < prompt.indexOf("# Personality"), prompt)
        assertTrue(prompt.indexOf("# Personality") < prompt.indexOf("Soul line 1"), prompt)
        assertTrue(prompt.indexOf("Soul line 2") < prompt.indexOf("World line 1"), prompt)
        assertTrue(prompt.indexOf("# World") < prompt.indexOf("World line 1"), prompt)
        assertTrue(prompt.indexOf("Soul line 2") < prompt.indexOf("# World"), prompt)
    }

    @Test
    fun `skips optional soul and world files when missing`() {
        val prompt = builder(dir.resolve("workspace")).buildSystemPrompt("sidekick", conversationId())

        assertTrue(prompt.contains("<identity>"), prompt)
        assertFalse(prompt.contains("SOUL.md"), prompt)
        assertFalse(prompt.contains("WORLD.md"), prompt)
    }

    @Test
    fun `embeds channel project context before operating rules`() {
        val workingDir = Files.createDirectories(dir.resolve("workspace"))
        val projectDir = Files.createDirectories(workingDir.resolve("projects/C123"))
        Files.writeString(projectDir.resolve("AGENTS.md"), "Project line 1\nProject line 2")
        Files.writeString(workingDir.resolve("RULES.md"), "Rules line 1\nRules line 2")

        val prompt = builder(workingDir).buildSystemPrompt("sidekick", conversationId())

        assertTrue(prompt.contains("# Project context"), prompt)
        assertTrue(prompt.contains("Project line 1\nProject line 2"), prompt)
        assertTrue(prompt.contains("# Operating rules"), prompt)
        assertTrue(prompt.contains("Rules line 1\nRules line 2"), prompt)
        assertTrue(prompt.indexOf("# Project context") < prompt.indexOf("# Operating rules"), prompt)
    }

    @Test
    fun `ignores legacy global project context`() {
        val workingDir = Files.createDirectories(dir.resolve("workspace"))
        val contextDir = Files.createDirectories(workingDir.resolve("global/context/C123"))
        Files.writeString(contextDir.resolve("AGENTS.md"), "Legacy project context")

        val prompt = builder(workingDir).buildSystemPrompt("sidekick", conversationId())

        assertFalse(prompt.contains("# Project context"), prompt)
        assertFalse(prompt.contains("Legacy project context"), prompt)
    }

    @Test
    fun `skips channel project context when missing`() {
        val prompt = builder(dir.resolve("workspace")).buildSystemPrompt("sidekick", conversationId())

        assertFalse(prompt.contains("# Project context"), prompt)
        assertFalse(prompt.contains("AGENTS.md"), prompt)
    }

    private fun builder(workingDir: Path): SystemPromptBuilder =
        SystemPromptBuilder(
            AgentConfig(
                name = "Sidekick",
                stateDir = dir.resolve("state").toString(),
                workingDir = workingDir.toString(),
            ),
        )

    private fun conversationId(): ConversationId = ConversationId("C123", "1700000000.000")
}
