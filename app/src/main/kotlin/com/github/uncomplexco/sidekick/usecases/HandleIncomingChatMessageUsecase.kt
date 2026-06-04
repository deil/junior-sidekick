package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.SidekickAgent
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.session.IncomingChatMessage
import com.github.uncomplexco.sidekick.application.session.SessionManager
import com.github.uncomplexco.sidekick.application.session.SessionMessage
import com.github.uncomplexco.sidekick.application.session.triggers.ChatTrigger
import com.github.uncomplexco.sidekick.application.session.triggers.ConversationTriggerPolicy
import com.github.uncomplexco.sidekick.application.session.triggers.ReplyDecisionInput
import com.github.uncomplexco.sidekick.application.session.triggers.ReplyDecisionService
import com.github.uncomplexco.sidekick.application.session.triggers.TriggerDecision
import com.github.uncomplexco.sidekick.ports.ChatConversationId
import com.github.uncomplexco.sidekick.ports.ChatPlatformAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class HandleIncomingChatMessageUsecase(
    private val agentConfig: AgentConfig,
    private val agent: SidekickAgent,
    private val sessionManager: SessionManager,
    private val triggerPolicy: ConversationTriggerPolicy,
    private val replyTrigger: ReplyDecisionService,
    private val turnPromptBuilder: TurnPromptBuilder,
) {
    suspend fun handle(
        conversationId: ChatConversationId,
        message: IncomingChatMessage,
        chat: ChatPlatformAdapter,
    ) {
        log.debug("{} @{}: {}", conversationId.logLabel(), message.sender.username, message.text)

        val decision =
            triggerPolicy.decide(
                messageId = message.id,
                trigger = message.trigger,
                conversationId = conversationId,
            )
        when (decision) {
            TriggerDecision.Ignore -> {
                log.debug("{} ignored trigger={}", conversationId.logLabel(), message.trigger)
                return
            }

            is TriggerDecision.Handle -> {
                try {
                    handle(message.copy(files = message.files.take(MAX_MESSAGE_FILES)), decision, chat)
                } finally {
                    chat.activity.clear()
                }
            }
        }
    }

    private suspend fun handle(
        message: IncomingChatMessage,
        decision: TriggerDecision.Handle,
        chat: ChatPlatformAdapter,
    ) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }

        val attachedFiles = chat.fileIngestor.ingest(decision.sessionId, message.files.take(MAX_MESSAGE_FILES))
        val currentMessage =
            SessionMessage(
                id = message.id,
                role = MessageRole.USER,
                author = message.sender,
                text = message.text,
                fileIds = attachedFiles.map { it.id },
                createdAtMs = message.createdAtMs,
                explicitMention = decision.explicitMention,
            )

        val turn =
            sessionManager.recordIncomingMessage(
                sessionId = decision.sessionId,
                seedHistory = decision.seedHistory,
                historyLoader = chat.historyLoader,
                message = currentMessage,
                files = attachedFiles,
            )

        val shouldReply =
            replyTrigger.decide(
                ReplyDecisionInput(
                    text = message.text,
                    isExplicitMention = decision.explicitMention,
                    isPrivateMessage = message.trigger == ChatTrigger.ASSISTANT_MESSAGE,
                    conversationContext =
                        turnPromptBuilder.buildThreadContext(
                            turn.sessionId,
                            turn.compactions,
                            turn.history,
                            turn.sessionFiles,
                        ),
                    hasAssistantHistory = turn.history.any { it.role == MessageRole.ASSISTANT },
                ),
            )

        if (shouldReply.shouldReply) {
            chat.activity.start()

            val agentReply = agent.runTurn(turn, currentMessage, chat)
            val replyMessageId = chat.reply.postReply(agentReply)

            sessionManager.recordAssistantReply(
                sessionId = decision.sessionId,
                turnId = turn.turnId,
                text = agentReply,
                replyId = replyMessageId.messageId,
                createdAtMs = replyMessageId.timestamp,
                originalMessageId = message.id,
            )
        } else {
            log.debug("Skipping reply for message id=${message.id}: ${shouldReply.reason} ${shouldReply.detail}")
            sessionManager.markMessageSkipped(
                sessionId = decision.sessionId,
                messageId = message.id,
                reason = shouldReply.reason.toString(),
            )
        }
    }

    companion object {
        private const val MAX_MESSAGE_FILES = 3
        private val log = LoggerFactory.getLogger(HandleIncomingChatMessageUsecase::class.java)
    }
}
