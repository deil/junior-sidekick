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
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.session.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.ChatPlatformAdapter
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
    private val systemPromptBuilder: SystemPromptBuilder,
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolRegistryFactory: TurnToolRegistryFactory,
) {
    suspend fun runTurn(
        ctx: TurnContext,
        message: SessionMessage,
        chat: ChatPlatformAdapter,
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
                                system(systemPromptBuilder.buildSystemPrompt(config.botUsername!!))
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
                    onToolCallStarting { toolCall ->
                        log.debug("onToolCallStarting: ${toolCall.toolName}")
                        chat.activity.start("Executing ${toolCall.toolName}...")
                    }

                    onToolCallCompleted { toolCall ->
                        log.debug("onToolCallCompleted: {} -> {}", toolCall.toolName, toolCall.toolResult)
                        chat.activity.clear()
                    }
                }
            }

        val input = turnPromptBuilder.buildSessionTurnPrompt(message, ctx)
        return agent.run(input, ctx.sessionId.lockKey())
    }

    private fun sidekickStrategy(message: SessionMessage) =
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
