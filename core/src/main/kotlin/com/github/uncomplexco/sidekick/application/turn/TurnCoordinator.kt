package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.skills.detectUserSkillInvocation
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.chat.SteeringInbox
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
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
    suspend fun run(trigger: TurnTrigger, inbox: SteeringInbox? = null) {
        when (trigger) {
            is TurnTrigger.Incoming -> runIncoming(trigger.batch, trigger.chat, inbox)
            is TurnTrigger.Scheduled -> runScheduled(trigger)
        }
    }

    private suspend fun runIncoming(
        batch: IncomingBatch,
        chat: ChatPlatformAdapter,
        inbox: SteeringInbox?,
    ) {
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
                    processIncoming(batch.messages, decision, chat, inbox)
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
        inbox: SteeringInbox?,
    ) {
        val turnStartedAt = TimeSource.Monotonic.markNow()
        val messages = incomingMessages.sortedBy { it.createdAtMs }
        val (currentMessages, attachedFiles) =
            prepareMessages(messages, decision.conversationId, chat)

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
                respondToIncoming(messages, turn, chat, turnStartedAt, inbox)
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
        inbox: SteeringInbox?,
    ) {
        val respondingMessages = messages.toMutableList()
        respondingMessages.forEach { chat.lifecycle.markProcessing(it) }

        try {
            val completed =
                executeAgentTurn(
                    turn.context.conversation.conversationId,
                    turn,
                    chat,
                    turnStartedAt,
                ) { currentTurn ->
                    val pending = inbox?.drain().orEmpty().sortedBy { it.createdAtMs }
                    if (pending.isEmpty()) null
                    else {
                        val (recorded, files) =
                            prepareMessages(pending, turn.context.conversation.conversationId, chat)
                        val updated = sessions.appendIncomingMessages(currentTurn, recorded, files)
                        respondingMessages += pending
                        pending.forEach { chat.lifecycle.markProcessing(it) }
                        log.info(
                            "{} steered turn={} messages={}",
                            turn.context.conversation.conversationId.lockKey(),
                            turn.context.turnId,
                            pending.map { it.id },
                        )
                        updated
                    }
                }
            if (completed) {
                respondingMessages.forEach { chat.lifecycle.markCompleted(it) }
            } else {
                respondingMessages.forEach { chat.lifecycle.markFailed(it) }
            }
        } catch (error: CancellationException) {
            respondingMessages.forEach { chat.lifecycle.markFailed(it) }
            throw error
        } catch (error: Exception) {
            respondingMessages.forEach { chat.lifecycle.markFailed(it) }
            throw error
        }
    }

    private suspend fun executeAgentTurn(
        conversationId: ConversationId,
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
        turnStartedAt: TimeMark,
        steer: suspend (RecordedTurn) -> RecordedTurn? = { null },
    ): Boolean {
        var currentTurn = turn
        val agentResult =
            try {
                agent.execute(turn, chat) { steer(currentTurn)?.also { currentTurn = it } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn(
                    "{} failed to generate reply for message id={}: {}",
                    conversationId.lockKey(),
                    currentTurn.context.lastMessageId,
                    error.message,
                    error,
                )
                sessions.markMessagesSkipped(
                    conversationId = conversationId,
                    messageIds = currentTurn.messages.map { it.id },
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
            originalMessageIds = currentTurn.messages.map { it.id },
            inputTokensConsumed = agentResult.stats.usage.inputTokenCount,
            outputTokensConsumed = agentResult.stats.usage.outputTokenCount,
        )
        return true
    }

    private suspend fun prepareMessages(
        messages: List<IncomingMessage>,
        conversationId: ConversationId,
        chat: ChatPlatformAdapter,
    ): Pair<List<SessionMessage>, List<IncomingChatFile>> {
        val attachedFiles = mutableListOf<IncomingChatFile>()
        val recorded = messages.map { message ->
            val files = chat.ingestFiles(conversationId, message.files.take(MAX_INCOMING_FILES))
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
        return recorded to attachedFiles
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
