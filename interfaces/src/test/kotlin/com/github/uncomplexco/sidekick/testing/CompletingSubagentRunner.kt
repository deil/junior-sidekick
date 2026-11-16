package com.github.uncomplexco.sidekick.testing

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import com.github.uncomplexco.sidekick.application.turn.ConversationContext

class CompletingSubagentRunner(private val answer: String) : SubagentRunner {
    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        conversation: ConversationContext,
    ): AgentRunResult<String> = AgentRunResult.Completed(answer, AgentUsageStats())
}
