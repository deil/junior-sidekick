package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.skills.detectUserSkillInvocation
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
import com.github.uncomplexco.sidekick.application.utils.Loggers
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component

@Component
class TurnExecutor(
    private val turnTrigger: InboundMessageFilter,
    private val conversationManager: ConversationManager,
    private val replyTrigger: ReplyDecisionService,
    private val agentConfig: AgentConfig,
    private val agent: AgentTurnRunner,
    private val skills: SkillCatalogProvider,
) {
    suspend fun run(
        conversationId: ChatConversationId,
        messages: List<InboundMessage>,
        chat: ChatPlatformAdapter,
    ) {
        try {
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

            chat.activity.start()
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
            chat.ingestFiles(
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
                explicitSkillInvocation =
                    detectUserSkillInvocation(message.text, skills.catalog())
                        ?.let { ExplicitSkillInvocation(it.skill.name) },
            )

        conversationManager.compactIfNeeded(decision.conversationId) { hook ->
            when (hook) {
                SessionContextCompactor.CompactionHook.PreCompaction -> chat.activity.`continue`("Compacting conversation...")
                SessionContextCompactor.CompactionHook.PostCompaction -> chat.activity.`continue`()
            }
        }

        val turn =
            conversationManager.recordIncomingMessages(
                conversationId = decision.conversationId,
                seedHistory = decision.seedHistory,
                historyLoader = chat::loadHistory,
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
                    messageHistory = turn.conversation.history.messages,
                    isExplicitMention = decision.explicitMention,
                    isPrivateMessage = message.type == ChatMessageType.ASSISTANT_MESSAGE,
                    hasAssistantHistory =
                        turn.conversation.history.messages
                            .any { it.role == SessionMessageRole.ASSISTANT },
                ),
            )

        if (shouldReply.shouldReply) {
            chat.activity.`continue`()
            conversationManager.setSubscribed(decision.conversationId, true)

            val agentReply =
                try {
                    agent.runTurn(turn, currentMessage, chat)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.warn(
                        "{} failed to generate reply for message id={}: {}",
                        decision.conversationId.lockKey(),
                        message.id,
                        error.message,
                        error,
                    )
                    conversationManager.markMessageSkipped(
                        conversationId = decision.conversationId,
                        messageId = message.id,
                        reason = AGENT_FAILURE_REASON,
                    )
                    runCatching { chat.postReply(TEMPORARY_FAILURE_REPLY) }
                    return
                }
            val replyMessageId = chat.postReply(agentReply)

            conversationManager.recordAssistantReply(
                conversationId = decision.conversationId,
                turnId = turn.turnId,
                text = agentReply,
                replyId = replyMessageId.messageId,
                createdAtMs = replyMessageId.timestamp,
                originalMessageId = message.id,
            )
        } else if (shouldReply.shouldUnsubscribe) {
            log.debug(
                "Unsubscribing session for message id=${message.id}: ${shouldReply.reason} ${shouldReply.detail}",
            )
            conversationManager.markMessageSkipped(
                conversationId = decision.conversationId,
                messageId = message.id,
                reason = shouldReply.reason.toString(),
            )
            conversationManager.setSubscribed(decision.conversationId, false)
            runCatching { chat.postReply(UNSUBSCRIBE_ACK) }
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
        private const val UNSUBSCRIBE_ACK = "Unsubscribed. Mention me to resume."
        private const val AGENT_FAILURE_REASON = "AGENT_FAILURE"
        private const val TEMPORARY_FAILURE_REPLY = "I hit a temporary model/provider error while processing this. Please retry in a minute."
        private val log = Loggers.TURN_EXECUTOR
    }
}
