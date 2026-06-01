package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.TurnMessage
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.application.sessions.SessionCompaction
import com.github.uncomplexco.sidekick.application.sessions.SessionMessage
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.timestamp
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import org.springframework.stereotype.Component
import java.time.Instant.ofEpochMilli

@Component
class PromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSystemPrompt(username: String): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt()
        sections += identitySection(username)
        sections += outputFormat()

        return sections.joinToString("\n\n")
    }

    fun baseSystemPrompt(): String =
        """
    |You are ${config.name}, a Slack-based helper assistant. Follow the personality block for voice and tone in every reply. 
    |
    |- In all communication, be concise, practical, and specific.
    |- Prefer actionable next steps over generic explanations.
    |- When the user gives a clear task, execute it immediately in this turn.
    |- Do not ask for permission to proceed when the request is already clear.
    |- If critical input is missing and cannot be discovered with tools, ask one direct clarifying question.
    |- Never guess. If you cannot verify with available sources, say it is unverified.
        """.trimMargin()

    fun identitySection(username: String) = xmlTag("identity", "Your Slack username is $username")

    fun outputFormat() =
        buildString {
            appendLine("<output format=\"slack-markdown\">")
            appendLine("- Start with the answer or result, not internal process narration.")
            appendLine(
                "- Use Slack-flavored Markdown: **bold** section labels, `code`, [text](url) links, bullet lists, and fenced code blocks. No hash-prefixed headings and no tables. When the answer primarily lists several URLs, show each URL bare instead of as a labeled link. Do not show bare Slack user IDs, mention them instead: <@[SLACK-USER-ID]>",
            )
            appendLine(
                "- Keep replies brief and scannable; use bullets or short code blocks when helpful, and one compact thread reply when it fits.",
            )
            appendLine(
                "- When a research or document-style answer would benefit from continuation, multiple sections, or future reference value, create a Slack canvas and keep the thread reply to one or two short sentences plus the link; do not recap the canvas contents.",
            )
            appendLine(
                "- Unless a successful Slack side-effect tool intentionally satisfied the request by itself, end every turn with a final user-facing markdown response.",
            )

            appendLine("</output>")
        }

    fun buildUserTurnPrompt(
        message: TurnMessage,
        ctx: TurnContext,
    ): String =
        buildString {
            if (ctx.history.isNotEmpty()) {
                appendLine(buildThreadContext(ctx.compactions, ctx.history))
            }

            append(xmlTag("current-instruction", "[${message.user.username}] ${message.text}"))
        }

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
                    "<compaction index=\"${index + 1}\" covered_messages=\"${compaction.coveredMessageIds.size}\" created_at=\"${timestamp(
                        compaction.createdAtMs,
                    )}\"/>"
                lines += compaction.summary
                lines += "</compaction>"
            }
            lines += "</thread-compactions>"
        }

        val transcript = history.mapIndexed { idx, message -> renderSessionMessage(idx, message) }.joinToString("\n")
        lines += xmlTag("thread-transcript", transcript)

        return lines.joinToString("\n")
    }

    private fun renderSessionMessage(
        idx: Int,
        message: SessionMessage,
    ): String {
        val lines = mutableListOf<String>()

        val authorUsername = message.author?.username ?: message.role.name.lowercase()

        lines +=
            "<message id=\"${message.id}\" sent_at=\"${ofEpochMilli(
                message.createdAtMs,
            )}\" role=\"${message.role.name.lowercase()}\" author=\"${escapeXml(authorUsername)}\">"

        val markers = mutableListOf<String>()
        if (message.replied == false) {
            markers += "assistant skipped: ${message.skippedReason ?: "no-reply route"}"
        }

        if (message.explicitMention) {
            markers += "explicit mention"
        }

        val markerSuffix = if (markers.isEmpty()) "" else " (${markers.joinToString("; ")})"
        val displayName =
            message.author?.username ?: if (message.role == MessageRole.ASSISTANT) config.name else message.role.name.lowercase()
        lines += "[${message.role.name.lowercase()}] $displayName: ${message.text}$markerSuffix"

        lines += "</message>"

        return lines.joinToString("\n")
    }
}
