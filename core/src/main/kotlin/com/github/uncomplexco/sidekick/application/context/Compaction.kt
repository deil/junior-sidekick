package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import org.springframework.stereotype.Component
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class SessionContextCompactor(
    private val summarizer: SessionContextSummarizer,
) {
    suspend fun compactIfNeeded(
        state: ConversationState,
        hooks: (hook: CompactionHook) -> Unit,
    ): Boolean {
        var usedContext = state.stats.totalTokens ?: 0
        if (usedContext <= COMPACTION_TRIGGER_TOKENS) {
            return false
        }

        hooks(CompactionHook.PreCompaction)

        val batchSize = maxOf(state.messages.size - MIN_LIVE_MESSAGES, 0)
        val batch = state.messages.take(batchSize)
        val summary = summarizer.summarize(state.id, state.compactions, batch)

        state.compactions +=
            SessionCompaction(
                id = generateCompactionId(),
                createdAtMs = Clock.System.now().toEpochMilliseconds(),
                summary = summary,
                coveredMessageIds = batch.map { it.id },
                assistantMessageCount = batch.count { it.role == SessionMessageRole.ASSISTANT },
            )
        state.messages = state.messages.drop(batchSize).toMutableList()
        state.koogMessages.clear()
        state.stats = state.stats.copy(totalTokens = 0, messages = state.messages.size, toolCalls = 0)

        hooks(CompactionHook.PostCompaction)
        return true
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateCompactionId(): String =
        "compaction_${System.currentTimeMillis()}_${Uuid.generateV7().toString().replace("-", "").take(8)}"

    enum class CompactionHook {
        PreCompaction,
        PostCompaction,
    }

    companion object {
        private const val MIN_LIVE_MESSAGES = 16
        private const val COMPACTION_TRIGGER_TOKENS = 200_000
        private const val TOKEN_ESTIMATE_CHARS = 4
    }
}
