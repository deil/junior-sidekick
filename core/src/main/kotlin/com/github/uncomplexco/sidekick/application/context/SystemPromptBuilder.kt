package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.utils.markdownSection
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.stereotype.Component

@Component
class SystemPromptBuilder(
    private val config: AgentConfig,
    private val platform: ChatPlatform,
    private val templates: MarkdownPromptTemplate = MarkdownPromptTemplate(),
) {
    fun buildSystemPrompt(
        username: String,
        project: ProjectContext,
    ): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt(platform)
        sections += identitySection(username)
        val workspace = config.workspaceLayout()
        optionalMarkdownSection(
                heading = "Personality",
                path = workspace.configDirectoryPath().resolve("SOUL.md"),
            )
            ?.also { sections += it }
        optionalMarkdownSection(
                heading = "World",
                path = workspace.configDirectoryPath().resolve("WORLD.md"),
            )
            ?.also { sections += it }
        project.instructions?.let { sections += markdownSection("Project context", it) }
        optionalMarkdownSection(
                heading = "Operating rules",
                path = workspace.configDirectoryPath().resolve("RULES.md"),
                variables = mapOf("NO_REPLY_MARKER" to NO_REPLY_MARKER),
            )
            ?.also { sections += it }

        return sections.joinToString("\n\n")
    }

    private fun baseSystemPrompt(platform: ChatPlatform): String =
        """
        You are ${config.name}, a ${platform.name.lowercase().replaceFirstChar(
            Char::uppercase
        )}-based helper assistant. Follow the personality block for voice and tone in every reply.
        """
            .trimIndent()

    private fun identitySection(username: String): String =
        xmlTag("identity", "Your username is $username")

    private fun optionalMarkdownSection(
        heading: String,
        path: Path,
        variables: Map<String, String> = emptyMap(),
    ): String? {
        if (!Files.isRegularFile(path)) {
            return null
        }

        val content = templates.render(Files.readString(path), path.toString(), variables)
        return markdownSection(heading, content.trimEnd())
    }

    companion object {
        const val NO_REPLY_MARKER = "[[NO_REPLY]]"
    }
}
