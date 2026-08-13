package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.AgentUsageStats
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class KoogSubagentRunner(
    private val agentConfig: AgentConfig,
    private val koogConfig: KoogConfig,
    private val subagents: SubagentCatalogProvider,
) : SubagentRunner {
    override suspend fun run(
        ctx: TurnContext,
        subagentType: String,
        prompt: String,
    ): SubagentRunResult {
        val aiModelProfile = koogConfig.normalProfile
        var toolCallCount = 0
        var inputTokenCount = 0L
        var outputTokenCount = 0L
        val systemPrompt =
            subagents
                .catalog()
                .subagents
                .firstOrNull { it.name == subagentType }
                ?.systemPrompt
                ?: throw IllegalArgumentException("Unknown subagent type: $subagentType")
        val agent =
            AIAgent(
                promptExecutor = koogConfig.openRouterExecutor(),
                agentConfig =
                    AIAgentConfig(
                        prompt =
                            prompt(
                                id = "sidekick-subagent-prompt",
                                params =
                                    koogConfig.openRouterParams(
                                        aiModelProfile,
                                        ctx.conversation.conversationId,
                                    ),
                            ) {
                                system(systemPrompt)
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = aiModelProfile.model,
                                capabilities = koogConfig.modelCapabilities(),
                            ),
                        maxAgentIterations = koogConfig.maxAgentIterations,
                    ),
                toolRegistry =
                    ToolRegistry {
                        tools(WorkspaceFileTools(ctx.conversation.virtualPaths))
                        tools(WebFetchTools(agentConfig.name))
                    },
            ) {
                handleEvents {
                    onLLMCallCompleted { llmCall ->
                        inputTokenCount += llmCall.response?.metaInfo?.inputTokensCount ?: 0
                        outputTokenCount += llmCall.response?.metaInfo?.outputTokensCount ?: 0
                    }
                    onToolCallStarting {
                        toolCallCount++
                    }
                }
            }

        return SubagentRunResult(
            output = agent.run(prompt, "subagent-${UUID.randomUUID()}"),
            stats =
                AgentUsageStats(
                    toolCallCount = toolCallCount,
                    inputTokenCount = inputTokenCount,
                    outputTokenCount = outputTokenCount,
                ),
        )
    }
}
