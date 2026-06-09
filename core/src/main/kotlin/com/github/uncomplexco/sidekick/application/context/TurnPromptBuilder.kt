package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
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
            if (!ctx.history.hasKoogMessages) {
                appendLine(buildThreadContext(ctx.conversationId, ctx.history.compactions, ctx.history.messages, ctx.sessionFiles))
            }

            val skippedMessages = pendingSkippedMessages(ctx)
            if (skippedMessages.isNotEmpty()) {
                appendLine(
                    xmlTag(
                        "skipped-messages",
                        buildString {
                            skippedMessages.forEachIndexed { idx, skippedMessage ->
                                appendLine(
                                    renderSessionMessage(
                                        idx,
                                        skippedMessage,
                                        ctx.conversationId,
                                        skippedMessage.fileIds.mapNotNull { fileId ->
                                            ctx.sessionFiles.find { file -> file.id == fileId }
                                        },
                                    ),
                                )
                            }
                        },
                    ),
                )
            }

            appendLine(
                xmlTag(
                    "requester",
                    buildString {
                        appendLine("user_id: ${message.author!!.username}")
                        appendLine("full_name: ${message.author.fullName}")
                    },
                ),
            )

            appendLine(xmlTag("current-instruction", "[${message.author!!.username}] ${message.text}"))

            if (message.fileIds.isNotEmpty()) {
                appendLine(
                    renderFileAttachments(
                        ctx.conversationId,
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
        conversationId: ConversationId,
        compactions: List<SessionCompaction>,
        history: List<SessionMessage>,
        sessionFiles: List<SessionFileRef>,
    ): String {
        val lines = mutableListOf<String>()

        lines +=
            xmlTag(
                "thread-context",
                buildString {
                    appendLine("channel_id: ${conversationId.channelId}")
                    appendLine("thread_ts: ${conversationId.threadId}")
                },
            )

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

        threadTranscript(conversationId, history, sessionFiles)?.also { lines += it }

        return lines.joinToString("\n")
    }

    private fun threadTranscript(
        conversationId: ConversationId,
        history: List<SessionMessage>,
        files: List<SessionFileRef>,
    ): String? {
        if (history.isNotEmpty()) {
            val transcript =
                history
                    .mapIndexed { idx, message ->
                        renderSessionMessage(
                            idx,
                            message,
                            conversationId,
                            message.fileIds.mapNotNull { fileId ->
                                files.find { file -> file.id == fileId }
                            },
                        )
                    }.joinToString("\n")
            return xmlTag("thread-transcript", transcript)
        }

        return null
    }

    private fun renderSessionMessage(
        idx: Int,
        message: SessionMessage,
        conversationId: ConversationId,
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
        lines += "${message.text}$markerSuffix"

        if (attachedFiles.isNotEmpty()) {
            lines += renderFileAttachments(conversationId, attachedFiles, config.stateDirectoryPath(), MAX_ATTACHMENT_BASE64_CHARS)
        }

        lines += "</message>"

        return lines.joinToString("\n")
    }

    private fun pendingSkippedMessages(ctx: TurnContext): List<SessionMessage> {
        if (!ctx.history.hasKoogMessages) {
            return emptyList()
        }

        val lastAssistantIndex = ctx.history.messages.indexOfLast { it.role == SessionMessageRole.ASSISTANT }
        return ctx.history.messages
            .drop(lastAssistantIndex + 1)
            .filter { it.role == SessionMessageRole.USER && it.replied == false }
    }

    companion object {
        private const val MAX_ATTACHMENT_BASE64_CHARS = 120_000
    }
}
