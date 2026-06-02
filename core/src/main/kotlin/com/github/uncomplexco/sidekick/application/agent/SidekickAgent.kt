package com.github.uncomplexco.sidekick.application.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.TurnMessage
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
    private val koogConfig: KoogConfig,
    private val promptBuilder: PromptBuilder,
    private val toolRegistryFactory: TurnToolRegistryFactory,
) {
    suspend fun runTurn(
        ctx: TurnContext,
        message: TurnMessage,
    ): String {
        val toolRegistry = toolRegistryFactory.build(ctx)
        val strategy = sidekickStrategy(message)

        val agent =
            AIAgent(
                promptExecutor = openRouterExecutor(koogConfig.openRouterApiKey),
                strategy = strategy,
                agentConfig =
                    AIAgentConfig(
                        prompt =
                            prompt(
                                id = "sidekick-base-prompt",
                                params = koogConfig.openRouterParams(),
                            ) {
                                system(promptBuilder.buildSystemPrompt(config.botUsername!!))
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = koogConfig.model,
                                capabilities = koogConfig.modelCapabilities(),
                            ),
                        maxAgentIterations = 50,
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

    private fun sidekickStrategy(message: TurnMessage) =
        strategy<String, String>("sidekick") {
            val classify by node<String, ReplyRoute>("classify") { input ->
                ReplyRoute(input, shouldReply = true)
            }
            val reply by nodeLLMRequest("reply")
            val executeTools by nodeExecuteTools("executeTools")
            val sendToolResults by nodeLLMSendToolResults("sendToolResults")

            edge(nodeStart forwardTo classify)
            edge(classify forwardTo reply onCondition { it.shouldReply } transformed { it.input })
            edge(classify forwardTo nodeFinish onCondition { !it.shouldReply } transformed { "" })
            edge(reply forwardTo executeTools onToolCalls { true })
            edge(reply forwardTo nodeFinish onTextMessage { true })
            edge(executeTools forwardTo sendToolResults)
            edge(sendToolResults forwardTo executeTools onToolCalls { true })
            edge(sendToolResults forwardTo nodeFinish onTextMessage { true })
        }
}

private data class ReplyRoute(
    val input: String,
    val shouldReply: Boolean,
)
