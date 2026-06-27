package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import org.springframework.stereotype.Component
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class SessionContextCompactor(
    private val turnPromptBuilder: TurnPromptBuilder,
    private val summarizer: SessionContextSummarizer,
) {
    suspend fun compactIfNeeded(state: ConversationState) {
        var estimatedTokens = estimateTokenCount(state)
        if (estimatedTokens <= COMPACTION_TRIGGER_TOKENS) {
            return
        }

        while (estimatedTokens > COMPACTION_TARGET_TOKENS && state.messages.size > MIN_LIVE_MESSAGES) {
            val compactableCount = state.messages.size - MIN_LIVE_MESSAGES

            val batchSize = minOf(COMPACTION_BATCH_SIZE, compactableCount)
            val batch = state.messages.take(batchSize)
            val summary = summarizer.summarize(state.id, batch, state.files)

            state.compactions +=
                SessionCompaction(
                    id = generateCompactionId(),
                    createdAtMs = Clock.System.now().toEpochMilliseconds(),
                    summary = summary,
                    coveredMessageIds = batch.map { it.id },
                    assistantMessageCount = batch.count { it.role == SessionMessageRole.ASSISTANT },
                )
            state.messages = state.messages.drop(batchSize).toMutableList()
            estimatedTokens = estimateTokenCount(state)
        }
    }

    private fun estimateTokenCount(state: ConversationState): Int =
        ceil(
            (
                turnPromptBuilder
                    .buildThreadContext(
                        state.id,
                        state.compactions,
                        state.messages,
                        emptyList(),
                    ).length
            ) / TOKEN_ESTIMATE_CHARS.toDouble(),
        ).toInt()

    @OptIn(ExperimentalUuidApi::class)
    private fun generateCompactionId(): String =
        "compaction_${System.currentTimeMillis()}_${Uuid.generateV7().toString().replace("-", "").take(8)}"

    companion object {
        private const val MIN_LIVE_MESSAGES = 12
        private const val COMPACTION_TRIGGER_TOKENS = 9000
        private const val COMPACTION_TARGET_TOKENS = 7000
        private const val COMPACTION_BATCH_SIZE = 24
        private const val TOKEN_ESTIMATE_CHARS = 4
    }
}
