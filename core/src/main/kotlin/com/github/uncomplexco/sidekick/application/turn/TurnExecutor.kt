package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.koog.SidekickAgent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TurnExecutor(
    private val turnTrigger: InboundMessageFilter,
    private val conversationManager: ConversationManager,
    private val replyTrigger: ReplyDecisionService,
    private val agentConfig: AgentConfig,
    private val agent: SidekickAgent,
) {
    suspend fun run(
        conversationId: ChatConversationId,
        messages: List<InboundMessage>,
        chat: ChatPlatformAdapter,
    ) {
        try {
            chat.activity.start()

            val decision =
                when (val triggerDecision = turnTrigger.shouldTriggerTurn(conversationId, messages)) {
                    TurnTriggerDecision.Ignore -> {
                        log.debug("{} ignored batch size={}", conversationId.logLabel(), messages.size)
                        return
                    }

                    is TurnTriggerDecision.ShouldHandle -> {
                        triggerDecision
                    }
                }

            messages.sortedBy { it.createdAtMs }.forEach { message ->
                handle(message.copy(files = message.files.take(MAX_MESSAGE_FILES)), decision, chat)
            }
        } finally {
            chat.activity.endTurn()
        }
    }

    private suspend fun handle(
        message: InboundMessage,
        decision: TurnTriggerDecision.ShouldHandle,
        chat: ChatPlatformAdapter,
    ) {
        if (message.files.isNotEmpty()) {
            val text = message.files.map { file -> "File: ${file.name} ${file.filetype} ${file.mimetype}" }
            log.debug(
                "Attached files: ${text.joinToString(", ")}",
            )
        }

        val attachedFiles =
            chat.fileIngestor.ingest(
                decision.conversationId,
                message.files.take(MAX_MESSAGE_FILES),
            )
        val currentMessage =
            SessionMessage(
                id = message.id,
                role = SessionMessageRole.USER,
                author = message.sender,
                text = message.text,
                fileIds = attachedFiles.map { it.id },
                createdAtMs = message.createdAtMs,
                explicitMention = decision.explicitMention,
            )

        val turn =
            conversationManager.recordIncomingMessages(
                conversationId = decision.conversationId,
                seedHistory = decision.seedHistory,
                historyLoader = chat.historyLoader,
                messages = listOf(currentMessage),
                files = attachedFiles,
            )

        val shouldReply =
            replyTrigger.shouldReply(
                ReplyDecisionInput(
                    text = message.text,
                    botUser =
                        MessageAuthor(
                            username = agentConfig.botUsername!!,
                            fullName = agentConfig.name,
                        ),
                    messageHistory = turn.history.messages,
                    isExplicitMention = decision.explicitMention,
                    isPrivateMessage = message.type == ChatMessageType.ASSISTANT_MESSAGE,
                    hasAssistantHistory = turn.history.messages.any { it.role == SessionMessageRole.ASSISTANT },
                ),
            )

        if (shouldReply.shouldReply) {
            chat.activity.`continue`()

            val agentReply = agent.runTurn(turn, currentMessage, chat)
            val replyMessageId = chat.reply.postReply(agentReply)

            conversationManager.recordAssistantReply(
                conversationId = decision.conversationId,
                turnId = turn.turnId,
                text = agentReply,
                replyId = replyMessageId.messageId,
                createdAtMs = replyMessageId.timestamp,
                originalMessageId = message.id,
            )
        } else {
            log.debug(
                "Skipping reply for message id=${message.id}: ${shouldReply.reason} ${shouldReply.detail}",
            )
            conversationManager.markMessageSkipped(
                conversationId = decision.conversationId,
                messageId = message.id,
                reason = shouldReply.reason.toString(),
            )
        }
    }

    companion object {
        private const val MAX_MESSAGE_FILES = 3
        private val log = LoggerFactory.getLogger("sidekick.turn-executor")
    }
}
