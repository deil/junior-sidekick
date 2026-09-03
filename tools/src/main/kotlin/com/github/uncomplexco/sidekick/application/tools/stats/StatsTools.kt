package com.github.uncomplexco.sidekick.application.tools.stats

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import kotlinx.serialization.Serializable
import java.time.Clock

class StatsTools(
    private val stats: WeeklyStatsService,
    private val clock: Clock = Clock.systemUTC(),
) : ToolSet {
    @Tool("stats__weekly")
    @LLMDescription("Get installation-wide usage statistics for the previous seven days.")
    fun weeklyStats(): WeeklyStatsResult {
        val gathered = stats.gather(clock.instant())
        return WeeklyStatsResult(
            channels = gathered.channels,
            conversations = gathered.conversations,
            tokens_consumed = gathered.tokensConsumed,
            users = gathered.users,
        )
    }
}

@Serializable
data class WeeklyStatsResult(
    val channels: Int,
    val conversations: Int,
    val tokens_consumed: Long,
    val users: Int,
)
