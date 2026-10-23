package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.skills.detectUserSkillInvocation
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import com.github.uncomplexco.sidekick.application.utils.Loggers
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component

@Component
class TurnCoordinator(
    private val admission: TurnAdmission,
    private val sessions: SessionManager,
    private val participation: TurnParticipation,
    private val agent: AgentTurnExecutor,
    private val skills: SkillCatalogProvider,
) {
    suspend fun run(trigger: TurnTrigger) {
        when (trigger) {
            is TurnTrigger.Incoming -> runIncoming(trigger.batch, trigger.chat)
            is TurnTrigger.Scheduled -> runScheduled(trigger)
        }
    }

    private suspend fun runIncoming(batch: IncomingBatch, chat: ChatPlatformAdapter) {
        try {
            when (val decision = admission.admit(batch)) {
                TurnAdmissionDecision.Ignore -> {
                    log.debug(
                        "{} ignored batch size={}",
                        batch.conversationId.logLabel(),
                        batch.messages.size,
                    )
                    return
                }

                is TurnAdmissionDecision.Admitted -> {
                    chat.lifecycle.begin()
                    processIncoming(batch.messages, decision, chat)
                }
            }
        } finally {
            chat.lifecycle.end()
        }
    }

    private suspend fun runScheduled(trigger: TurnTrigger.Scheduled) {
        val turnStartedAt = TimeSource.Monotonic.markNow()
        val currentMessage = scheduledMessage(trigger.job, trigger.startedAtMs)

        trigger.chat.lifecycle.begin()
        try {
            val turn =
                sessions.recordIncomingMessages(
                    conversationId = trigger.conversationId,
                    seedHistory = false,
                    historyLoader = { emptyList() },
                    messages = listOf(currentMessage),
                    files = emptyList(),
                )
            executeAgentTurn(trigger.conversationId, turn, trigger.chat, turnStartedAt)
        } finally {
            trigger.chat.lifecycle.end()
        }
    }

    private suspend fun processIncoming(
        incomingMessages: List<IncomingMessage>,
        decision: TurnAdmissionDecision.Admitted,
        chat: ChatPlatformAdapter,
    ) {
        val turnStartedAt = TimeSource.Monotonic.markNow()
        val messages =
            incomingMessages
                .sortedBy { it.createdAtMs }
                .map {
                    it.copy(files = it.files.take(MAX_INCOMING_FILES))
                }
        val attachedFiles = mutableListOf<IncomingChatFile>()
        val currentMessages = messages.map { message ->
            if (message.files.isNotEmpty()) {
                val files =
                    message.files.map { file ->
                        "File: ${file.name} ${file.filetype} ${file.mimetype}"
                    }
                log.debug("Attached files: ${files.joinToString(", ")}")
            }
            val files = chat.ingestFiles(decision.conversationId, message.files)
            attachedFiles += files
            SessionMessage(
                id = message.id,
                role = SessionMessageRole.USER,
                author = message.sender,
                text = message.text,
                fileIds = files.map { it.id },
                createdAtMs = message.createdAtMs,
                explicitMention = message.type == IncomingMessageType.EXPLICIT_MENTION,
                explicitSkillInvocation =
                    detectUserSkillInvocation(message.text, skills.catalog())?.let {
                        ExplicitSkillInvocation(it.skill.name)
                    },
            )
        }

        sessions.compactIfNeeded(decision.conversationId) { hook ->
            when (hook) {
                SessionContextCompactor.CompactionHook.PreCompaction ->
                    chat.lifecycle.progress("Compacting conversation...")
                SessionContextCompactor.CompactionHook.PostCompaction -> chat.lifecycle.progress()
            }
        }

        val turn =
            sessions.recordIncomingMessages(
                conversationId = decision.conversationId,
                seedHistory = decision.seedHistory,
                historyLoader = chat::loadHistory,
                messages = currentMessages,
                files = attachedFiles,
            )
        val participationDecision =
            participation.decide(
                TurnParticipationInput(
                    messages = messages,
                    turn = turn,
                )
            )

        when (participationDecision) {
            is TurnParticipationDecision.Respond -> {
                sessions.setSubscribed(decision.conversationId, true)
                respondToIncoming(messages, turn, chat, turnStartedAt)
            }

            is TurnParticipationDecision.Unsubscribe -> {
                log.debug(
                    "Unsubscribing session for message ids={}: {} {}",
                    messages.map { it.id },
                    participationDecision.reason,
                    participationDecision.detail,
                )
                sessions.markMessagesSkipped(
                    conversationId = decision.conversationId,
                    messageIds = messages.map { it.id },
                    reason = participationDecision.reason.toString(),
                )
                sessions.setSubscribed(decision.conversationId, false)
                runCatching { chat.delivery.deliverUnsubscribedAck() }
            }

            is TurnParticipationDecision.Skip -> {
                log.debug(
                    "Skipping reply for message ids={}: {} {}",
                    messages.map { it.id },
                    participationDecision.reason,
                    participationDecision.detail,
                )
                sessions.markMessagesSkipped(
                    conversationId = decision.conversationId,
                    messageIds = messages.map { it.id },
                    reason = participationDecision.reason.toString(),
                )
            }
        }
    }

    private suspend fun respondToIncoming(
        messages: List<IncomingMessage>,
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
        turnStartedAt: TimeMark,
    ) {
        messages.forEach { chat.lifecycle.markProcessing(it) }

        try {
            val completed =
                executeAgentTurn(
                    turn.context.conversation.conversationId,
                    turn,
                    chat,
                    turnStartedAt,
                )
            if (completed) {
                messages.forEach { chat.lifecycle.markCompleted(it) }
            } else {
                messages.forEach { chat.lifecycle.markFailed(it) }
            }
        } catch (error: CancellationException) {
            messages.forEach { chat.lifecycle.markFailed(it) }
            throw error
        } catch (error: Exception) {
            messages.forEach { chat.lifecycle.markFailed(it) }
            throw error
        }
    }

    private suspend fun executeAgentTurn(
        conversationId: com.github.uncomplexco.sidekick.application.conversation.ConversationId,
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
        turnStartedAt: TimeMark,
    ): Boolean {
        val agentResult =
            try {
                agent.execute(turn, chat)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn(
                    "{} failed to generate reply for message id={}: {}",
                    conversationId.lockKey(),
                    turn.context.lastMessageId,
                    error.message,
                    error,
                )
                sessions.markMessagesSkipped(
                    conversationId = conversationId,
                    messageIds = turn.messages.map { it.id },
                    reason = AGENT_FAILURE_REASON,
                )
                runCatching { chat.delivery.deliverRuntimeFailure(error) }
                return false
            }

        val agentReply = agentResult.response
        val replyMessageId =
            try {
                chat.delivery.deliver(
                    agentReply,
                    ResponseMetadata.Turn(
                        TurnStats(
                            profileName = agentResult.stats.profileName,
                            executionTimeSeconds = turnStartedAt.elapsedNow().inWholeSeconds,
                            toolCallCount = agentResult.stats.usage.toolCallCount,
                            inputTokenCount = agentResult.stats.usage.inputTokenCount,
                            outputTokenCount = agentResult.stats.usage.outputTokenCount,
                        )
                    ),
                )
            } finally {
                agentReply.deleteAttachments()
            }

        sessions.recordAssistantReply(
            conversationId = conversationId,
            text = agentReply.text,
            replyId = replyMessageId.messageId,
            createdAtMs = replyMessageId.timestamp,
            originalMessageIds = turn.messages.map { it.id },
            inputTokensConsumed = agentResult.stats.usage.inputTokenCount,
            outputTokensConsumed = agentResult.stats.usage.outputTokenCount,
        )
        return true
    }

    private fun scheduledMessage(job: ScheduledJob, startedAtMs: Long): SessionMessage =
        SessionMessage(
            id = "scheduled_${job.id}_$startedAtMs",
            role = SessionMessageRole.USER,
            author = MessageAuthor(username = "scheduled-job-${job.id}", fullName = job.name),
            text = job.prompt,
            createdAtMs = startedAtMs,
            explicitSkillInvocation =
                detectUserSkillInvocation(job.prompt, skills.catalog())?.let {
                    ExplicitSkillInvocation(it.skill.name)
                },
        )

    companion object {
        private const val MAX_INCOMING_FILES = 5
        private const val AGENT_FAILURE_REASON = "AGENT_FAILURE"
        private val log = Loggers.TURN_EXECUTOR
    }
}
