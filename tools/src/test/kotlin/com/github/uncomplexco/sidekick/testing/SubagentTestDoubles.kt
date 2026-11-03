package com.github.uncomplexco.sidekick.testing

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import kotlinx.coroutines.CancellationException

class RecordingSubagentRunner(
    private val result: String,
    private val usage: AgentUsageStats = AgentUsageStats(),
) : SubagentRunner {
    val prompts = mutableListOf<String>()
    val subagentTypes = mutableListOf<String>()
    val agentIds = mutableListOf<String>()
    val runIds = mutableListOf<RunId>()
    val virtualPaths = mutableListOf<VirtualPaths>()

    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        virtualPaths: VirtualPaths,
    ): AgentRunResult<String> {
        agentIds += agentId
        runIds += runId
        this.virtualPaths += virtualPaths
        subagentTypes += subagentType
        prompts += prompt
        return AgentRunResult.Completed(result, usage)
    }
}

class FailingSubagentRunner(
    private val failure: Exception = IllegalStateException("boom"),
    private val usage: AgentUsageStats = AgentUsageStats(),
) : SubagentRunner {
    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        virtualPaths: VirtualPaths,
    ): AgentRunResult<String> = AgentRunResult.Failed(failure, usage)
}

class CancellingSubagentRunner(private val cancellation: CancellationException) : SubagentRunner {
    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        virtualPaths: VirtualPaths,
    ): AgentRunResult<String> = throw cancellation
}
