package com.github.uncomplexco.sidekick.application.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.TurnMessage
import com.github.uncomplexco.sidekick.application.config.AgentConfigMeh
import com.github.uncomplexco.sidekick.application.context.PromptBuilder
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SidekickAgent::class.java)

fun interface TurnToolRegistryFactory {
    fun build(ctx: TurnContext): ToolRegistry
}

@Component
class SidekickAgent(
    private val config: AgentConfig,
    private val configMeh: AgentConfigMeh,
    private val promptBuilder: PromptBuilder,
    private val toolRegistryFactory: TurnToolRegistryFactory,
) {
    suspend fun runTurn(
        ctx: TurnContext,
        message: TurnMessage,
    ): String {
        val toolRegistry = toolRegistryFactory.build(ctx)

        val agent =
            AIAgent(
                promptExecutor = openRouterExecutor(configMeh.openRouterApiKey),
                agentConfig =
                    AIAgentConfig(
                        prompt =
                            prompt(
                                id = "sidekick-base-prompt",
                                params = configMeh.openRouterParams(),
                            ) {
                                system(promptBuilder.buildSystemPrompt(config.botUsername!!))
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = configMeh.model,
                                capabilities = configMeh.modelCapabilities(),
                            ),
                        maxAgentIterations = 10,
                    ),
                toolRegistry = toolRegistry,
            ) {
                handleEvents {
                    onToolCallStarting { ctx ->
                        log.debug("onToolCallStarting: ${ctx.toolName}")
                    }

                    onToolCallCompleted { ctx ->
                        log.debug("onToolCallCompleted: {} -> {}", ctx.toolName, ctx.toolResult)
                    }
                }
            }

        val input = promptBuilder.buildUserTurnPrompt(message, ctx)
        return agent.run(input)
    }
}
