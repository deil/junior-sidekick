package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.markdownSection
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import org.springframework.stereotype.Component
import java.nio.file.Files

@Component
class SystemPromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSystemPrompt(
        username: String,
        conversationId: ConversationId,
    ): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt()
        sections += identitySection(username)
        val workspace = config.workspaceLayout()
        optionalMarkdownSection(heading = "Personality", path = workspace.configDirectoryPath().resolve("SOUL.md"))?.also { sections += it }
        optionalMarkdownSection(heading = "World", path = workspace.configDirectoryPath().resolve("WORLD.md"))?.also { sections += it }
        optionalProjectContext(conversationId)?.also { sections += it }
        optionalMarkdownSection(heading = "Operating rules", path = workspace.configDirectoryPath().resolve("RULES.md"))?.also { sections += it }

        return sections.joinToString("\n\n")
    }

    fun baseSystemPrompt(): String =
        """
        You are ${config.name}, a Slack-based helper assistant. Follow the personality block for voice and tone in every reply. 
        """.trimIndent()

    fun identitySection(username: String) = xmlTag("identity", "Your Slack username is $username")

    private fun optionalMarkdownSection(
        heading: String,
        path: java.nio.file.Path,
    ): String? {
        if (!Files.isRegularFile(path)) {
            return null
        }

        return markdownSection(heading, Files.readString(path).trimEnd())
    }

    private fun optionalProjectContext(conversationId: ConversationId): String? {
        val path =
            config
                .workspaceLayout()
                .projectWorkspacesDirectoryPath()
                .resolve(sanitizePathSegment(conversationId.channelId))
                .resolve("AGENTS.md")
        if (!Files.isRegularFile(path)) {
            return null
        }

        return markdownSection("Project context", Files.readString(path).trimEnd())
    }
}
