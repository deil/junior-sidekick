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
class TurnPromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSessionTurnPrompt(
        message: SessionMessage,
        ctx: TurnContext,
    ): String =
        buildString {
            if (ctx.history.isNotEmpty()) {
                appendLine(buildThreadContext(ctx.sessionId, ctx.compactions, ctx.history, ctx.sessionFiles))
            }

            append(
                xmlTag(
                    "requester",
                    buildString {
                        appendLine("user_id: ${message.author!!.username}")
                        appendLine("full_name: ${message.author.fullName}")
                    },
                ),
            )

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
