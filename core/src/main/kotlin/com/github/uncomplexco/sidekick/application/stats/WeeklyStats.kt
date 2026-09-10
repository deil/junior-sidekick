package com.github.uncomplexco.sidekick.application.stats

import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component

data class WeeklyStats(
    val channels: Int,
    val conversations: Int,
    val tokensConsumed: Long,
    val users: Int,
)

data class ConversationUsage(
    val channelId: String,
    val userIds: Set<String>,
    val consumedInputTokens: Long,
    val consumedOutputTokens: Long,
)

@Component
class WeeklyStatsService(private val conversations: ConversationStateStore) {
    fun gather(executedAt: Instant): WeeklyStats {
        val periodStartMs = executedAt.minus(REPORTING_PERIOD).toEpochMilli()
        val periodEndMs = executedAt.toEpochMilli()
        val selected = conversations.loadUsageStartedBetween(periodStartMs, periodEndMs)

        return WeeklyStats(
            channels = selected.map { it.channelId }.distinct().size,
            conversations = selected.size,
            tokensConsumed = selected.sumOf { it.consumedInputTokens + it.consumedOutputTokens },
            users = selected.flatMap { it.userIds }.distinct().size,
        )
    }

    private companion object {
        val REPORTING_PERIOD: Duration = Duration.ofDays(7)
    }
}
