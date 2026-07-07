package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class KoogSubagentRunner(
    private val agentConfig: AgentConfig,
    private val koogConfig: KoogConfig,
    private val agentDefinitions: AgentDefinitionCatalog,
) : SubagentRunner {
    override suspend fun run(
        ctx: TurnContext,
        subagentType: String,
        prompt: String,
    ): String {
        val aiModelProfile = koogConfig.normalProfile
        val systemPrompt = agentDefinitions.systemPrompt(subagentType)
        val agent =
            AIAgent(
                promptExecutor = openRouterExecutor(koogConfig.openRouterApiKey),
                agentConfig =
                    AIAgentConfig(
                        prompt =
                            prompt(
                                id = "sidekick-subagent-prompt",
                                params = koogConfig.openRouterParams(aiModelProfile),
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
                installFeatures = {
                    install(OpenTelemetry) {
                        setServiceInfo(agentConfig.name, "1.0.0")
                    }
                },
            )

        return agent.run(prompt, "subagent-${UUID.randomUUID()}")
    }
}
