package com.github.uncomplexco.sidekick.application.agent.koog

import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import kotlin.time.TimeSource
import org.slf4j.LoggerFactory

@PublishedApi
internal class AgentRunLog(
    private val runId: RunId,
    private val agentId: String,
) {
    private val log = LoggerFactory.getLogger(KoogAgent::class.java)
    private val startedAt = TimeSource.Monotonic.markNow()

    fun started(model: String) {
        log.info(
            "Agent started: conversation={} agent={} run={} model={}",
            runId.conversationId,
            agentId,
            runId.runId,
            model,
        )
    }

    fun completed(usage: AgentUsageStats) {
        log.info(
            "Agent completed: conversation={} agent={} run={} duration_ms={} " +
                "tool_calls={} input_tokens={} output_tokens={}",
            runId.conversationId,
            agentId,
            runId.runId,
            startedAt.elapsedNow().inWholeMilliseconds,
            usage.toolCallCount,
            usage.inputTokenCount,
            usage.outputTokenCount,
        )
    }

    fun failed(usage: AgentUsageStats, error: Exception) {
        log.warn(
            "Agent failed: conversation={} agent={} run={} duration_ms={} " +
                "tool_calls={} input_tokens={} output_tokens={}",
            runId.conversationId,
            agentId,
            runId.runId,
            startedAt.elapsedNow().inWholeMilliseconds,
            usage.toolCallCount,
            usage.inputTokenCount,
            usage.outputTokenCount,
            error,
        )
    }

    fun cancelled() {
        log.debug(
            "Agent cancelled: conversation={} agent={} run={} duration_ms={}",
            runId.conversationId,
            agentId,
            runId.runId,
            startedAt.elapsedNow().inWholeMilliseconds,
        )
    }
}
