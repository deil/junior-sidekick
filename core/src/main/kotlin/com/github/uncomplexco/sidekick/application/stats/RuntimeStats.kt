package com.github.uncomplexco.sidekick.application.stats

data class AgentUsageStats(
    val toolCallCount: Int = 0,
    val inputTokenCount: Long = 0,
    val outputTokenCount: Long = 0,
) {
    operator fun plus(other: AgentUsageStats): AgentUsageStats =
        AgentUsageStats(
            toolCallCount = toolCallCount + other.toolCallCount,
            inputTokenCount = inputTokenCount + other.inputTokenCount,
            outputTokenCount = outputTokenCount + other.outputTokenCount,
        )
}

data class AgentStats(
    val profileName: String,
    val usage: AgentUsageStats,
)

data class TurnStats(
    val profileName: String,
    val executionTimeSeconds: Long,
    val toolCallCount: Int,
    val inputTokenCount: Long,
    val outputTokenCount: Long,
)
