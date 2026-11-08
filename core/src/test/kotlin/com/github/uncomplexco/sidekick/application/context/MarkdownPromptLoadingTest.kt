package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

class MarkdownPromptLoadingTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @CsvSource("true,rich,plain", "false,plain,rich")
    fun `Spring flag selects instructions from every loaded Markdown source`(
        enabled: Boolean,
        selected: String,
        excluded: String,
    ) {
        val configDir = Files.createDirectories(dir.resolve("config"))
        val projectRoot = Files.createDirectories(dir.resolve("project"))
        Files.writeString(
            configDir.resolve("SOUL.md"),
            "{% if features.chat_ui %}rich personality{% else %}plain personality{% endif %}",
        )
        Files.writeString(
            configDir.resolve("WORLD.md"),
            "{% if features.chat_ui %}rich world{% else %}plain world{% endif %}",
        )
        Files.writeString(
            configDir.resolve("RULES.md"),
            "{% if features.chat_ui %}rich rules{% else %}plain rules{% endif %}\nReturn {{NO_REPLY_MARKER}}.",
        )
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "---\ntags: [engineering]\n---\n{% if features.chat_ui %}rich project{% else %}plain project{% endif %}",
        )

        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource("test", mapOf("agent.chat-ui.enabled" to enabled))
            )
            context.register(MarkdownPromptTemplate::class.java)
            context.refresh()
            val templates = context.getBean(MarkdownPromptTemplate::class.java)
            val project = ProjectContexts(templates).resolve(projectRoot)
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString())

            val prompt =
                SystemPromptBuilder(config, ChatPlatform.SLACK, templates)
                    .buildSystemPrompt("helper", project)

            assertTrue(prompt.contains("$selected personality"), prompt)
            assertTrue(prompt.contains("$selected world"), prompt)
            assertTrue(prompt.contains("$selected rules"), prompt)
            assertEquals("$selected project", project.instructions)
            assertEquals(setOf("engineering"), project.tags)
            assertFalse(prompt.contains(excluded), prompt)
            assertTrue(prompt.contains("Return [[NO_REPLY]]."), prompt)
            assertFalse(prompt.contains("{%"), prompt)
        }
    }

    @Test
    fun `a malformed project template reports a project configuration error`() {
        Files.writeString(dir.resolve("AGENTS.md"), "{% if features.chat_ui %}Missing endif")

        val failure =
            assertThrows<ProjectConfigurationException> {
                ProjectContexts().resolve(dir)
            }

        assertTrue(failure.message.orEmpty().contains("AGENTS.md"))
    }
}
