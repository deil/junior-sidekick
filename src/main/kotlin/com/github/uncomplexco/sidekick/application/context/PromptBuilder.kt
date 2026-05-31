package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.TurnMessage
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.application.sessions.SessionCompaction
import com.github.uncomplexco.sidekick.application.sessions.SessionMessage
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import org.springframework.stereotype.Component
import java.time.Instant.ofEpochMilli

@Component
class PromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSystemPrompt(): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt()

        return sections.joinToString("\n\n")
    }

    fun buildUserMessage(
        message: TurnMessage,
        ctx: TurnContext,
    ): String =
        buildString {
            if (ctx.history.isNotEmpty()) {
                appendLine(buildThreadTranscript(ctx))
            }

            appendLine("<current-message>")
            appendLine("[${message.user}] ${message.text}")
            append("</current-message>")
        }

    fun buildThreadTranscript(ctx: TurnContext): String? = buildThreadContext(ctx.compactions, ctx.history)

    fun buildThreadContext(
        compactions: List<SessionCompaction>,
        history: List<SessionMessage>,
    ): String? {
        if (history.isEmpty() && compactions.isEmpty()) {
            return null
        }

        val lines = mutableListOf<String>()

        if (compactions.isNotEmpty()) {
            lines += "<thread-compactions>"
            compactions.forEachIndexed { index, compaction ->
                lines +=
                    "summary_${index + 1}:\n${compaction.summary}\n\ncovered_messages: ${compaction.coveredMessageIds.size}\ncreated_at: ${
                        ofEpochMilli(
                            compaction.createdAtMs,
                        )
                    }\n"
            }
            lines += "</thread-compactions>"
        }

        lines += "<thread-transcript>"
        history.forEach { lines += renderConversationMessageLine(it) }
        lines += "</thread-transcript>"

        return lines.joinToString("\n")
    }

    private fun baseSystemPrompt(): String =
        """
    |You are ${config.name}, a Slack-based helper assistant.
    |
    |- In all communication, be concise, practical, and specific.
    |- Prefer actionable next steps over generic explanations.
    |- When the user gives a clear task, execute it immediately in this turn.
    |- Do not ask for permission to proceed when the request is already clear.
    |- If critical input is missing and cannot be discovered with tools, ask one direct clarifying question.
    |- Never guess. If you cannot verify with available sources, say it is unverified.
        """.trimMargin()

    private fun renderConversationMessageLine(message: SessionMessage): String {
        val displayName =
            message.author?.username ?: if (message.role == MessageRole.ASSISTANT) config.name else message.role.name.lowercase()
        val markers = mutableListOf<String>()
        if (message.replied == false) {
            markers += "assistant skipped: ${message.skippedReason ?: "no-reply route"}"
        }

        if (message.explicitMention) {
            markers += "explicit mention"
        }

        val markerSuffix = if (markers.isEmpty()) "" else " (${markers.joinToString("; ")})"
        return "[${message.role.name.lowercase()}] $displayName: ${message.text}$markerSuffix"
    }
}
