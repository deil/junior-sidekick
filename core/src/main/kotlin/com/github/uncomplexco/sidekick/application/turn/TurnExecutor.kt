package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.skills.detectUserSkillInvocation
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.TurnStats
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
import com.github.uncomplexco.sidekick.application.utils.Loggers
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Component
class TurnExecutor(
    private val turnTrigger: InboundMessageFilter,
    private val conversationManager: ConversationManager,
    private val replyTrigger: ReplyDecisionService,
    private val agentConfig: AgentConfig,
    private val agent: AgentTurnRunner,
    private val skills: SkillCatalogProvider,
) {
    suspend fun runScheduled(
        conversationId: ConversationId,
        job: ScheduledJob,
        chat: ChatPlatformAdapter,
        startedAtMs: Long = System.currentTimeMillis(),
    ) {
        val turnStartedAt = TimeSource.Monotonic.markNow()
        val currentMessage =
            SessionMessage(
                id = "scheduled_${job.id}_$startedAtMs",
                role = SessionMessageRole.USER,
                author = MessageAuthor(username = "scheduled-job-${job.id}", fullName = job.name),
                text = job.prompt,
                createdAtMs = startedAtMs,
                explicitSkillInvocation =
                    detectUserSkillInvocation(job.prompt, skills.catalog())
                        ?.let { ExplicitSkillInvocation(it.skill.name) },
            )

        chat.resultHandler.start()
        try {
            val turn =
                conversationManager.recordIncomingMessages(
                    conversationId = conversationId,
                    seedHistory = false,
                    historyLoader = { emptyList() },
                    messages = listOf(currentMessage),
                    files = emptyList(),
                )
            executeAgentTurn(conversationId, turn, currentMessage, chat, turnStartedAt)
        } finally {
            chat.resultHandler.endTurn()
        }
    }

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

            chat.resultHandler.start()
            messages.sortedBy { it.createdAtMs }.forEach { message ->
                handle(message.copy(files = message.files.take(MAX_MESSAGE_FILES)), decision, chat)
            }
        } finally {
            chat.resultHandler.endTurn()
        }
    }

    private suspend fun handle(
        message: InboundMessage,
        decision: TurnTriggerDecision.ShouldHandle,
        chat: ChatPlatformAdapter,
    ) {
        val turnStartedAt = TimeSource.Monotonic.markNow()

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
                SessionContextCompactor.CompactionHook.PreCompaction -> chat.resultHandler.`continue`("Compacting conversation...")
                SessionContextCompactor.CompactionHook.PostCompaction -> chat.resultHandler.`continue`()
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
                    conversationId = decision.conversationId,
                    hasAssistantHistory =
                        turn.conversation.history.messages
                            .any { it.role == SessionMessageRole.ASSISTANT },
                ),
            )

        if (shouldReply.shouldReply) {
            conversationManager.setSubscribed(decision.conversationId, true)
            chat.resultHandler.markProcessing(message)

            try {
                val completed = executeAgentTurn(decision.conversationId, turn, currentMessage, chat, turnStartedAt)
                if (completed) {
                    chat.resultHandler.markCompleted(message)
                } else {
                    chat.resultHandler.markFailed(message)
                }
            } catch (error: CancellationException) {
                chat.resultHandler.markFailed(message)
                throw error
            } catch (error: Exception) {
                chat.resultHandler.markFailed(message)
                throw error
            }
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
            runCatching { chat.resultHandler.postUnsubscribed() }
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

    private suspend fun executeAgentTurn(
        conversationId: ConversationId,
        turn: TurnContext,
        currentMessage: SessionMessage,
        chat: ChatPlatformAdapter,
        turnStartedAt: TimeMark,
    ): Boolean {
        val agentResult =
            try {
                agent.runTurn(turn, currentMessage, chat)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn(
                    "{} failed to generate reply for message id={}: {}",
                    conversationId.lockKey(),
                    currentMessage.id,
                    error.message,
                    error,
                )
                conversationManager.markMessageSkipped(
                    conversationId = conversationId,
                    messageId = currentMessage.id,
                    reason = AGENT_FAILURE_REASON,
                )
                runCatching { chat.resultHandler.postRuntimeFailure(error) }
                return false
            }

        val agentReply = agentResult.reply
        val replyMessageId =
            try {
                chat.resultHandler.postReply(
                    agentReply,
                    TurnStats(
                        profileName = agentResult.stats.profileName,
                        executionTimeSeconds = turnStartedAt.elapsedNow().inWholeSeconds,
                        toolCallCount = agentResult.stats.usage.toolCallCount,
                        inputTokenCount = agentResult.stats.usage.inputTokenCount,
                        outputTokenCount = agentResult.stats.usage.outputTokenCount,
                    ),
                )
            } finally {
                agentReply.deleteAttachments()
            }

        conversationManager.recordAssistantReply(
            conversationId = conversationId,
            turnId = turn.turnId,
            text = agentReply.text,
            replyId = replyMessageId.messageId,
            createdAtMs = replyMessageId.timestamp,
            originalMessageId = currentMessage.id,
            inputTokensConsumed = agentResult.stats.usage.inputTokenCount,
            outputTokensConsumed = agentResult.stats.usage.outputTokenCount,
        )
        return true
    }

    companion object {
        const val MAX_MESSAGE_FILES = 5
        const val AGENT_FAILURE_REASON = "AGENT_FAILURE"
        private val log = Loggers.TURN_EXECUTOR
    }
}
