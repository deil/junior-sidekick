package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.session.SessionCompaction
import com.github.uncomplexco.sidekick.application.session.SessionState
import org.springframework.stereotype.Component
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class SessionContextCompactor(
    private val promptBuilder: PromptBuilder,
    private val summarizer: SessionContextSummarizer,
) {
    suspend fun compactIfNeeded(state: SessionState) {
        var estimatedTokens = estimateTokenCount(state)
        if (estimatedTokens <= COMPACTION_TRIGGER_TOKENS) {
            return
        }

        while (estimatedTokens > COMPACTION_TARGET_TOKENS && state.messages.size > MIN_LIVE_MESSAGES) {
            val compactableCount = state.messages.size - MIN_LIVE_MESSAGES

            val batchSize = minOf(COMPACTION_BATCH_SIZE, compactableCount)
            val batch = state.messages.take(batchSize)
            val summary = summarizer.summarize(batch)

            state.compactions +=
                SessionCompaction(
                    id = generateCompactionId(),
                    createdAtMs = Clock.System.now().toEpochMilliseconds(),
                    summary = summary,
                    coveredMessageIds = batch.map { it.id },
                    assistantMessageCount = batch.count { it.role == MessageRole.ASSISTANT },
                )
            state.messages = state.messages.drop(batchSize).toMutableList()
            estimatedTokens = estimateTokenCount(state)
        }
    }

    private fun estimateTokenCount(state: SessionState): Int =
        ceil(
            (
                promptBuilder.buildThreadContext(state.compactions, state.messages)?.length
                    ?: 0
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
