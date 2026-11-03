package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.koog.KoogAgent
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import org.springframework.stereotype.Component

@Component
class KoogSubagentRunner(
    private val koogConfig: KoogConfig,
    private val subagents: SubagentCatalogProvider,
) : SubagentRunner {
    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
    ): AgentRunResult<String> {
        val systemPrompt =
            subagents.catalog().subagents.firstOrNull { it.name == subagentType }?.systemPrompt
                ?: throw IllegalArgumentException("Unknown subagent type: $subagentType")

        return KoogAgent(koogConfig, agentId)
            .run(
                runId = runId,
                tools = tools,
                model = koogConfig.normalProfile,
                systemPrompt = { systemPrompt },
                input = { prompt },
            )
    }
}
