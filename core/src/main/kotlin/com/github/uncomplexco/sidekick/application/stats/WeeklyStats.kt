package com.github.uncomplexco.sidekick.application.stats

import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class WeeklyStats(
    val projects: Int,
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
class WeeklyStatsService(
    private val conversations: ConversationStateStore,
) {
    fun gather(executedAt: Instant): WeeklyStats {
        val periodStartMs = executedAt.minus(REPORTING_PERIOD).toEpochMilli()
        val periodEndMs = executedAt.toEpochMilli()
        val selected = conversations.loadUsageStartedBetween(periodStartMs, periodEndMs)

        return WeeklyStats(
            projects = selected.map { it.channelId }.distinct().size,
            conversations = selected.size,
            tokensConsumed =
                selected.sumOf {
                    it.consumedInputTokens + it.consumedOutputTokens
                },
            users = selected.flatMap { it.userIds }.distinct().size,
        )
    }

    private companion object {
        val REPORTING_PERIOD: Duration = Duration.ofDays(7)
    }
}
