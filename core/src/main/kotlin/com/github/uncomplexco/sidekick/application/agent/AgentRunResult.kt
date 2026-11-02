package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats

sealed interface AgentRunResult<out T> {
    val usage: AgentUsageStats

    data class Completed<T>(val output: T, override val usage: AgentUsageStats) : AgentRunResult<T>

    data class Failed(val cause: Exception, override val usage: AgentUsageStats) :
        AgentRunResult<Nothing>
}
