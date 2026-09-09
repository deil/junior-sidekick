package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.utils.markdownSection
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class SystemPromptBuilder(
    private val config: AgentConfig,
    private val platform: ChatPlatform,
) {
    fun buildSystemPrompt(
        username: String,
        projectRoot: Path,
    ): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt(platform)
        sections += identitySection(username)
        val workspace = config.workspaceLayout()
        optionalMarkdownSection(heading = "Personality", path = workspace.configDirectoryPath().resolve("SOUL.md"))?.also { sections += it }
        optionalMarkdownSection(heading = "World", path = workspace.configDirectoryPath().resolve("WORLD.md"))?.also { sections += it }
        optionalProjectContext(projectRoot)?.also { sections += it }
        optionalMarkdownSection(heading = "Operating rules", path = workspace.configDirectoryPath().resolve("RULES.md"))?.also { sections += it }

        return sections.joinToString("\n\n")
    }

    private fun baseSystemPrompt(platform: ChatPlatform): String =
        """
        You are ${config.name}, a ${platform.name.lowercase().replaceFirstChar(Char::uppercase)}-based helper assistant. Follow the personality block for voice and tone in every reply.
        """.trimIndent()

    private fun identitySection(username: String): String = xmlTag("identity", "Your username is $username")

    private fun optionalMarkdownSection(
        heading: String,
        path: Path,
    ): String? {
        if (!Files.isRegularFile(path)) {
            return null
        }

        return markdownSection(heading, Files.readString(path).trimEnd())
    }

    private fun optionalProjectContext(projectRoot: Path): String? {
        val path = projectRoot.resolve("AGENTS.md")
        if (!Files.isRegularFile(path)) {
            return null
        }

        return markdownSection("Project context", Files.readString(path).trimEnd())
    }
}
