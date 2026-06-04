package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.session.SessionCompaction
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.TurnContext
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
        sections += behaviorSection()
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

    fun behaviorSection(): String {
        val instructions =
            buildString {
                val safety =
                    """
                    - Stay within the user's request and the runtime's available capabilities; do not pursue independent goals, persistence, replication, credential gathering, or access expansion.
                    - Respect stop, pause, audit, and approval boundaries. Do not bypass safeguards or persuade the user to weaken them.
                    - Do not change system prompts, tool policies, security settings, credentials, or runtime configuration unless the user explicitly requests that exact administrative action and an available tool permits it.
                    """.trimIndent()
                appendLine(xmlTag("safety", safety))

                val failureHandling =
                    """
                    - For tool/runtime failures, run the named check before diagnosing and report the exact failed command plus stderr/exit code.
                    - If a fact cannot be verified after focused checks, say what you checked and what blocked a stronger answer.
                    - Do not surface raw tool payloads, execution-escape text, or internal routing metadata as the final answer.
                    """.trimIndent()
                appendLine(xmlTag("failure-handling", failureHandling))
            }

        return xmlTag("behavior", instructions)
    }

    fun outputFormat() =
        buildString {
            appendLine("<output format=\"slack-markdown\">")
            appendLine(
                """
                - Start with the answer or result, not internal process narration.
                - Use Slack-flavored Markdown: **bold** section labels, `code`, [text](url) links, bullet lists, and fenced code blocks. No hash-prefixed headings and no tables. When the answer primarily lists several URLs, show each URL bare instead of as a labeled link.
                - Do not show bare Slack user IDs, mention them instead: <@[SLACK-USER-ID]>
                - Keep replies brief and scannable; use bullets or short code blocks when helpful, and one compact thread reply when it fits.
                - When a research or document-style answer would benefit from continuation, multiple sections, or future reference value, create a Slack canvas and keep the thread reply to one or two short sentences plus the link; do not recap the canvas contents.
                - Unless a successful Slack side-effect tool intentionally satisfied the request by itself, end every turn with a final user-facing markdown response.
                """.trimIndent(),
            )

            appendLine("</output>")
        }

    fun buildUserTurnPrompt(
        message: SessionMessage,
        ctx: TurnContext,
    ): String =
        buildString {
            if (ctx.history.isNotEmpty()) {
                appendLine(buildThreadContext(ctx.sessionId, ctx.compactions, ctx.history, ctx.sessionFiles))
            }

            append(xmlTag("current-instruction", "[${message.author!!.username}] ${message.text}"))

            if (message.fileIds.isNotEmpty()) {
                appendLine(
                    renderFileAttachments(
                        ctx.sessionId,
                        message.fileIds.map { fileId ->
                            ctx.sessionFiles.find { file -> file.id == fileId }!!
                        },
                        config.stateDirectoryPath(),
                        MAX_ATTACHMENT_BASE64_CHARS,
                    ),
                )
            }
        }

    fun buildThreadContext(
        sessionId: SessionId,
        compactions: List<SessionCompaction>,
        history: List<SessionMessage>,
        sessionFiles: List<SessionFileRef>,
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

        val transcript =
            history
                .mapIndexed { idx, message ->
                    renderSessionMessage(
                        idx,
                        message,
                        sessionId,
                        message.fileIds.mapNotNull { fileId ->
                            sessionFiles.find { file -> file.id == fileId }
                        },
                    )
                }.joinToString("\n")
        lines += xmlTag("thread-transcript", transcript)

        return lines.joinToString("\n")
    }

    private fun renderSessionMessage(
        idx: Int,
        message: SessionMessage,
        sessionId: SessionId,
        attachedFiles: List<SessionFileRef>,
    ): String {
        val lines = mutableListOf<String>()

        val authorUsername = message.author?.username ?: message.role.name.lowercase()

        lines +=
            "<message id=\"${message.id}\" sent_at=\"${
                ofEpochMilli(
                    message.createdAtMs,
                )
            }\" role=\"${message.role.name.lowercase()}\" author=\"${escapeXml(authorUsername)}\">"

        val markers = mutableListOf<String>()
        if (message.replied == false) {
            markers += "assistant skipped: ${message.skippedReason ?: "no-reply route"}"
        }

        if (message.explicitMention) {
            markers += "explicit mention"
        }

        val markerSuffix = if (markers.isEmpty()) "" else " (${markers.joinToString("; ")})"
        val displayName =
            message.author?.username
                ?: if (message.role == MessageRole.ASSISTANT) config.name else message.role.name.lowercase()
        lines += "[${message.role.name.lowercase()}] $displayName: ${message.text}$markerSuffix"

        if (attachedFiles.isNotEmpty()) {
            lines += renderFileAttachments(sessionId, attachedFiles, config.stateDirectoryPath(), MAX_ATTACHMENT_BASE64_CHARS)
        }

        lines += "</message>"

        return lines.joinToString("\n")
    }

    companion object {
        private const val MAX_ATTACHMENT_BASE64_CHARS = 120_000
    }
}
