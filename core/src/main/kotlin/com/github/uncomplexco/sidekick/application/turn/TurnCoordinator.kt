package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.skills.detectUserSkillInvocation
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
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
                    batch.messages
                        .sortedBy { it.createdAtMs }
                        .forEach { message -> processIncoming(message, decision, chat) }
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
                sessions.recordIncomingMessage(
                    conversationId = trigger.conversationId,
                    seedHistory = false,
                    historyLoader = { emptyList() },
                    message = currentMessage,
                    files = emptyList(),
                )
            executeAgentTurn(trigger.conversationId, turn, trigger.chat, turnStartedAt)
        } finally {
            trigger.chat.lifecycle.end()
        }
    }

    private suspend fun processIncoming(
        incomingMessage: IncomingMessage,
        decision: TurnAdmissionDecision.Admitted,
        chat: ChatPlatformAdapter,
    ) {
        val turnStartedAt = TimeSource.Monotonic.markNow()
        val message = incomingMessage.copy(files = incomingMessage.files.take(MAX_INCOMING_FILES))

        if (message.files.isNotEmpty()) {
            val files = message.files.map { file -> "File: ${file.name} ${file.filetype} ${file.mimetype}" }
            log.debug("Attached files: ${files.joinToString(", ")}")
        }

        val attachedFiles =
            chat.ingestFiles(
                decision.conversationId,
                message.files,
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
                    detectUserSkillInvocation(message.text, skills.catalog())?.let {
                        ExplicitSkillInvocation(it.skill.name)
                    },
            )

        sessions.compactIfNeeded(decision.conversationId) { hook ->
            when (hook) {
                SessionContextCompactor.CompactionHook.PreCompaction ->
                    chat.lifecycle.progress("Compacting conversation...")
                SessionContextCompactor.CompactionHook.PostCompaction -> chat.lifecycle.progress()
            }
        }

        val turn =
            sessions.recordIncomingMessage(
                conversationId = decision.conversationId,
                seedHistory = decision.seedHistory,
                historyLoader = chat::loadHistory,
                message = currentMessage,
                files = attachedFiles,
            )
        val participationDecision =
            participation.decide(
                TurnParticipationInput(
                    message = message,
                    turn = turn,
                    explicitMention = decision.explicitMention,
                )
            )

        when (participationDecision) {
            is TurnParticipationDecision.Respond -> {
                sessions.setSubscribed(decision.conversationId, true)
                chat.lifecycle.markProcessing(message)

                try {
                    val completed =
                        executeAgentTurn(
                            decision.conversationId,
                            turn,
                            chat,
                            turnStartedAt,
                        )
                    if (completed) {
                        chat.lifecycle.markCompleted(message)
                    } else {
                        chat.lifecycle.markFailed(message)
                    }
                } catch (error: CancellationException) {
                    chat.lifecycle.markFailed(message)
                    throw error
                } catch (error: Exception) {
                    chat.lifecycle.markFailed(message)
                    throw error
                }
            }

            is TurnParticipationDecision.Unsubscribe -> {
                log.debug(
                    "Unsubscribing session for message id=${message.id}: ${participationDecision.reason} ${participationDecision.detail}"
                )
                sessions.markMessageSkipped(
                    conversationId = decision.conversationId,
                    messageId = message.id,
                    reason = participationDecision.reason.toString(),
                )
                sessions.setSubscribed(decision.conversationId, false)
                runCatching { chat.delivery.deliverUnsubscribedAck() }
            }

            is TurnParticipationDecision.Skip -> {
                log.debug(
                    "Skipping reply for message id=${message.id}: ${participationDecision.reason} ${participationDecision.detail}"
                )
                sessions.markMessageSkipped(
                    conversationId = decision.conversationId,
                    messageId = message.id,
                    reason = participationDecision.reason.toString(),
                )
            }
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
                    turn.message.id,
                    error.message,
                    error,
                )
                sessions.markMessageSkipped(
                    conversationId = conversationId,
                    messageId = turn.message.id,
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
            turnId = turn.context.turnId,
            text = agentReply.text,
            replyId = replyMessageId.messageId,
            createdAtMs = replyMessageId.timestamp,
            originalMessageId = turn.message.id,
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
