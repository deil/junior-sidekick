package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.bolt.App
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale

@Component
@ConditionalOnProperty(name = ["agent.weekly-stats.enabled"], havingValue = "true")
class WeeklyStatsReporter(
    private val app: App,
    private val agentConfig: AgentConfig,
    private val weeklyStats: WeeklyStatsService,
    @Value("\${agent.weekly-stats.channel-id}") private val channelId: String,
) {
    init {
        require(channelId.isNotBlank()) { "agent.weekly-stats.channel-id must be configured when weekly stats are enabled" }
    }

    @Scheduled(cron = "\${agent.weekly-stats.cron}")
    fun postWeeklyStats() {
        Loggers.WEEKLY_STATS.info("Weekly stats job started: channel={}", channelId)

        val stats = weeklyStats.gather(Instant.now())
        Loggers.WEEKLY_STATS.info(
            "Weekly stats gathered: projects={} conversations={} users={} tokens_consumed={}",
            stats.projects,
            stats.conversations,
            stats.users,
            stats.tokensConsumed,
        )
        val message =
            """
            📊 *${agentConfig.name} weekly roundup*

            Here’s what happened over the last 7 days:

            • 🗂️ *Projects:* ${formatCount(stats.projects.toLong())}
            • 💬 *Conversations:* ${formatCount(stats.conversations.toLong())}
            • 👥 *People engaged:* ${formatCount(stats.users.toLong())}
            • 🧠 *Tokens consumed:* ${formatCount(stats.tokensConsumed)}
            """.trimIndent()

        val response = app.client().chatPostMessage { request -> request.channel(channelId).text(message) }
        check(response.isOk) { "Slack weekly stats post failed: ${response.error}" }

        Loggers.WEEKLY_STATS.info("Weekly stats job finished: channel={}", channelId)
    }
}

internal fun formatCount(count: Long): String {
    val (value, suffix) =
        when {
            count >= 1_000_000 -> count / 1_000_000.0 to "M"
            count >= 1_000 -> count / 1_000.0 to "K"
            else -> return count.toString()
        }

    return String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") + suffix
}
