package com.github.uncomplexco.sidekick.application.turn.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
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
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SidekickAgent::class.java)

fun interface ToolRegistryFactory {
    suspend fun build(
        ctx: TurnContext,
        chat: ChatPlatformAdapter,
    ): ToolRegistry
}

interface McpServersRegistry {
    suspend fun connect(
        conversationId: ConversationId,
        userId: String,
    ): List<ConnectedMcpServer>
}

interface ConnectedMcpServer {
    val id: String
    val toolRegistry: ToolRegistry

    suspend fun close()
}

@Component
class SidekickAgent(
    private val config: AgentConfig,
    private val koogConfig: KoogConfig,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val turnPromptBuilder: TurnPromptBuilder,
    private val toolRegistryFactory: ToolRegistryFactory,
    private val mcpServersRegistry: McpServersRegistry,
    private val chatHistoryProvider: ChatHistoryProvider,
) : AgentTurnRunner {
    override suspend fun runTurn(
        ctx: TurnContext,
        message: SessionMessage,
        chat: ChatPlatformAdapter,
    ): String {
        val mcpServers = mcpServersRegistry.connect(ctx.conversation.conversationId, message.author?.username.orEmpty())
        val ctxWithMcp = ctx.copy(conversation = ctx.conversation.copy(mcpServers = mcpServers))

        try {
            val mcpToolRegistry = mcpServers.fold(ToolRegistry.EMPTY) { acc, server -> acc + server.toolRegistry }
            val toolRegistry = toolRegistryFactory.build(ctxWithMcp, chat) + mcpToolRegistry
            val aiModelProfile = koogConfig.profile(ctx.aiModelProfile)

            val agent =
                AIAgent(
                    strategy = sidekickStrategy(message),
                    promptExecutor = openRouterExecutor(koogConfig.openRouterApiKey),
                    agentConfig =
                        AIAgentConfig(
                            prompt =
                                prompt(
                                    id = "sidekick-base-prompt",
                                    params = koogConfig.openRouterParams(aiModelProfile),
                                ) {
                                    system(systemPromptBuilder.buildSystemPrompt(config.botUsername!!, ctx.conversation.conversationId))
                                },
                            model =
                                LLModel(
                                    provider = LLMProvider.OpenRouter,
                                    id = aiModelProfile.model,
                                    capabilities = koogConfig.modelCapabilities(),
                                ),
                            maxAgentIterations = koogConfig.maxAgentIterations,
                        ),
                    toolRegistry = toolRegistry,
                ) {
                    install(ChatMemory) {
                        chatHistoryProvider = this@SidekickAgent.chatHistoryProvider
                    }

                    install(OpenTelemetry) {
                        setServiceInfo(config.name, "1.0.0")
                    }

                    handleEvents {
                        onToolCallStarting { toolCall ->
                            log.debug("onToolCallStarting: ${toolCall.toolName}")
                        }

                        onToolCallFailed { toolCall ->
                            log.debug("onToolCallFailed: {} -> {}", toolCall.toolName, toolCall.message)
                        }

                        onToolCallCompleted { toolCall ->
                            log.debug("onToolCallCompleted: {} -> {}", toolCall.toolName, toolCall.toolResult)
                        }
                    }
                }

            val input = turnPromptBuilder.buildSessionTurnPrompt(message, ctxWithMcp)
            return agent.run(input, ctx.conversation.conversationId.lockKey())
        } finally {
            mcpServers.forEach { it.close() }
        }
    }
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

private data class ReplyRoute(
    val input: String,
    val shouldReply: Boolean,
)

fun interface AgentTurnRunner {
    suspend fun runTurn(
        ctx: TurnContext,
        message: SessionMessage,
        chat: ChatPlatformAdapter,
    ): String
}
