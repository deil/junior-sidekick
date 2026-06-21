package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
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

        val prompt = builder(workingDir).buildSystemPrompt("sidekick")

        assertTrue(prompt.contains("# Personality"), prompt)
        assertTrue(prompt.contains("Soul line 1\nSoul line 2"), prompt)
        assertTrue(prompt.contains("# World"), prompt)
        assertTrue(prompt.contains("World line 1\nWorld line 2"), prompt)
        assertTrue(prompt.indexOf("<identity>") < prompt.indexOf("# Personality"), prompt)
        assertTrue(prompt.indexOf("# Personality") < prompt.indexOf("Soul line 1"), prompt)
        assertTrue(prompt.indexOf("Soul line 2") < prompt.indexOf("World line 1"), prompt)
        assertTrue(prompt.indexOf("# World") < prompt.indexOf("World line 1"), prompt)
        assertTrue(prompt.indexOf("World line 2") < prompt.indexOf("<behavior>"), prompt)
    }

    @Test
    fun `skips optional soul and world files when missing`() {
        val prompt = builder(dir.resolve("workspace")).buildSystemPrompt("sidekick")

        assertTrue(prompt.contains("<identity>"), prompt)
        assertTrue(prompt.contains("<behavior>"), prompt)
        assertFalse(prompt.contains("SOUL.md"), prompt)
        assertFalse(prompt.contains("WORLD.md"), prompt)
    }

    private fun builder(workingDir: Path): SystemPromptBuilder =
        SystemPromptBuilder(
            AgentConfig(
                name = "Sidekick",
                stateDir = dir.resolve("state").toString(),
                workingDir = workingDir.toString(),
            ),
        )
}
