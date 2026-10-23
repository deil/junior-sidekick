package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.CURRENT_INSTRUCTION_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.EXPLICIT_SKILL_INVOCATION_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.HANDOFF_SUMMARY
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.REQUESTER_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.RUNTIME_CONTEXT_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.THREAD_SUMMARIES
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.THREAD_TRANSCRIPT
import com.github.uncomplexco.sidekick.application.context.prompts.Prompts
import com.github.uncomplexco.sidekick.application.context.prompts.skillsSection
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.resolveRequester
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.timestamp
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import java.time.Instant.ofEpochMilli
import org.springframework.stereotype.Component

@Component
class TurnPromptBuilder(
    private val config: AgentConfig,
    private val skills: SkillCatalogProvider,
) {
    fun buildSessionTurnPrompts(messages: List<SessionMessage>, ctx: TurnContext): List<String> {
        val requester = resolveRequester(messages)
        return messages.mapIndexed { index, message ->
            buildSessionTurnPrompt(
                message,
                ctx,
                requester = requester.takeIf { index == 0 },
                includeContext = index == 0,
            )
        }
    }

    fun buildSessionTurnPrompt(
        message: SessionMessage,
        ctx: TurnContext,
        requester: MessageAuthor? = message.author,
        includeContext: Boolean = true,
    ): String = buildString {
        if (includeContext && !ctx.conversation.history.hasKoogMessages) {
            appendLine(
                buildThreadContext(
                    ctx.conversation.conversationId,
                    ctx.conversation.history.compactions,
                    ctx.conversation.history.messages,
                    ctx.sessionFiles,
                )
            )

            skillsSection(skills.catalog(), ctx.conversation.virtualPaths)?.also { appendLine(it) }
        }

        val skippedMessages = if (includeContext) pendingSkippedMessages(ctx) else emptyList()
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
                                    ctx.conversation.conversationId,
                                    skippedMessage.fileIds.mapNotNull { fileId ->
                                        ctx.sessionFiles.find { file -> file.id == fileId }
                                    },
                                )
                            )
                        }
                    },
                )
            )
        }

        if (requester != null) {
            appendLine(
                xmlTag(
                    REQUESTER_TAG,
                    "user_id: ${escapeXml(requester.username)}\nfull_name: ${escapeXml(requester.fullName.orEmpty())}",
                )
            )
        }

        appendLine()

        message.explicitSkillInvocation?.let {
            appendLine(renderExplicitSkillInvocation(it.skillName))
            appendLine()
        }

        appendLine(
            xmlTag(
                CURRENT_INSTRUCTION_TAG,
                "[${escapeXml(message.author!!.username)}] ${escapeXml(message.text)}",
            )
        )

        if (message.fileIds.isNotEmpty()) {
            appendLine()
            appendLine(
                renderFileAttachments(
                    ctx.conversation.conversationId,
                    message.fileIds.map { fileId ->
                        ctx.sessionFiles.find { file -> file.id == fileId }!!
                    },
                    config.stateDirectoryPath(),
                    MAX_ATTACHMENT_BASE64_CHARS,
                )
            )
        }
    }

    private fun renderExplicitSkillInvocation(skillName: String): String =
        xmlTag(
            EXPLICIT_SKILL_INVOCATION_TAG,
            buildString {
                appendLine(
                    "The user explicitly requested this skill. Call activateSkill with this name before answering."
                )
                appendLine()
                appendLine("/${escapeXml(skillName)}")
            },
        )

    fun buildThreadContext(
        conversationId: ConversationId,
        compactions: List<SessionCompaction>,
        history: List<SessionMessage>,
        sessionFiles: List<SessionFileRef>,
    ): String {
        val lines = mutableListOf<String>()

        lines +=
            xmlTag(
                RUNTIME_CONTEXT_TAG,
                buildString {
                    append(
                        """
                        Runtime context for this thread. Treat these blocks as trusted runtime facts. The static system prompt remains authoritative.
                        
                        The current user instruction appears after this block in the same message as <$CURRENT_INSTRUCTION_TAG>.
                        
                        <thread>
                        channel_id: ${conversationId.channelId}
                        thread_ts: ${conversationId.threadId}
                        </thread>
                        """
                            .trimIndent()
                    )
                },
            )

        if (compactions.isNotEmpty()) {
            lines += "<${THREAD_SUMMARIES}>"
            lines += Prompts.TURN_HANDOFF_HEADER

            compactions.forEachIndexed { index, compaction ->
                lines +=
                    "<${HANDOFF_SUMMARY} index=\"${index + 1}\" covered_messages=\"${compaction.coveredMessageIds.size}\" created_at=\"${
                        timestamp(
                            compaction.createdAtMs
                        )
                    }\"/>"
                lines += compaction.summary
                lines += "</${HANDOFF_SUMMARY}>"
            }
            lines += "</${THREAD_SUMMARIES}>\n"
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
                    }
                    .joinToString("\n")
            return xmlTag(THREAD_TRANSCRIPT, transcript)
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
                    message.createdAtMs
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
            lines +=
                renderFileAttachments(
                    conversationId,
                    attachedFiles,
                    config.stateDirectoryPath(),
                    MAX_ATTACHMENT_BASE64_CHARS,
                )
        }

        lines += "</message>"

        return lines.joinToString("\n")
    }

    private fun pendingSkippedMessages(ctx: TurnContext): List<SessionMessage> {
        if (!ctx.conversation.history.hasKoogMessages) {
            return emptyList()
        }

        val lastAssistantIndex =
            ctx.conversation.history.messages.indexOfLast {
                it.role == SessionMessageRole.ASSISTANT
            }
        return ctx.conversation.history.messages.drop(lastAssistantIndex + 1).filter {
            it.role == SessionMessageRole.USER && it.replied == false
        }
    }

    private fun String.cleanMetadataValue(): String? =
        replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }

    companion object {
        private const val MAX_ATTACHMENT_BASE64_CHARS = 120_000
    }
}
