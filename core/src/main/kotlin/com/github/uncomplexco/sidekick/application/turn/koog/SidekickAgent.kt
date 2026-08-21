package com.github.uncomplexco.sidekick.application.turn.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.checkpoints.TurnCheckpointPersistence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

private val log = LoggerFactory.getLogger(SidekickAgent::class.java)

interface ToolRegistryFactory {
    suspend fun buildExecutionTools(
        ctx: TurnContext,
        chat: ChatPlatformAdapter,
        replyAttachments: ReplyAttachmentCollector,
        onSubagentCompleted: (AgentUsageStats) -> Unit,
    ): ToolRegistry

    suspend fun buildOrchestrationTools(
        toolRegistry: ToolRegistry,
        chat: ChatPlatformAdapter,
        ctx: TurnContext,
    ): ToolRegistry
}

interface McpServersRegistry {
    suspend fun connect(
        conversationId: ConversationId,
        userId: String,
        workRoot: Path,
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
    private val checkpointPersistence: TurnCheckpointPersistence,
) : AgentTurnRunner {
    override suspend fun runTurn(
        ctx: TurnContext,
        message: SessionMessage,
        chat: ChatPlatformAdapter,
    ): AgentTurnResult {
        var usage = AgentUsageStats()

        val mcpServers =
            mcpServersRegistry.connect(
                ctx.conversation.conversationId,
                message.author?.username.orEmpty(),
                ctx.conversation.virtualPaths.workRoot,
            )
        val ctxWithMcp = ctx.copy(conversation = ctx.conversation.copy(mcpServers = mcpServers))
        val replyAttachments = ReplyAttachmentCollector(ctx.conversation.virtualPaths)

        try {
            val mcpToolRegistry = mcpServers.fold(ToolRegistry.EMPTY) { acc, server -> acc + server.toolRegistry }
            val baseTools =
                toolRegistryFactory.buildExecutionTools(ctxWithMcp, chat, replyAttachments) { subagentUsage ->
                    usage += subagentUsage
                } + mcpToolRegistry
            val toolRegistry =
                baseTools + toolRegistryFactory.buildOrchestrationTools(toolRegistry = baseTools, chat = chat, ctx = ctxWithMcp)
            val aiModelProfile = koogConfig.profile(ctx.aiModelProfile)

            val agent =
                AIAgent(
                    strategy = sidekickStrategy(),
                    promptExecutor = koogConfig.openRouterExecutor(),
                    agentConfig =
                        AIAgentConfig(
                            prompt =
                                prompt(
                                    id = "sidekick-base-prompt",
                                    params =
                                        koogConfig.openRouterParams(
                                            aiModelProfile,
                                            ctx.conversation.conversationId,
                                        ),
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

                    install(Persistence) {
                        enableAutomaticPersistence = true
                        storage = checkpointPersistence.forTurn(ctx.turnId)
                    }

                    handleEvents {
                        onLLMCallCompleted { llmCall ->
                            usage +=
                                AgentUsageStats(
                                    inputTokenCount =
                                        llmCall.response
                                            ?.metaInfo
                                            ?.inputTokensCount
                                            ?.toLong() ?: 0,
                                    outputTokenCount =
                                        llmCall.response
                                            ?.metaInfo
                                            ?.outputTokensCount
                                            ?.toLong() ?: 0,
                                )
                        }

                        onToolCallStarting { toolCall ->
                            usage += AgentUsageStats(toolCallCount = 1)
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
            val reply = ChatReply(agent.run(input, ctx.conversation.conversationId.lockKey()), replyAttachments.collected())
            return AgentTurnResult(
                reply = reply,
                stats =
                    AgentTurnStats(
                        profileName = ctx.aiModelProfile.name.lowercase(),
                        usage = usage,
                    ),
            )
        } catch (error: Exception) {
            replyAttachments.clear()
            throw error
        } finally {
            mcpServers.forEach { it.close() }
        }
    }
}

internal fun sidekickStrategy() =
    strategy<String, String>("sidekick") {
        val requestModelResponse by nodeLLMRequest("requestModelResponse")
        val executeToolCalls by nodeExecuteTools("executeToolCalls")
        val requestModelResponseWithToolResults by nodeLLMSendToolResults("requestModelResponseWithToolResults")

        edge(nodeStart forwardTo requestModelResponse)
        edge(requestModelResponse forwardTo executeToolCalls onToolCalls { true })
        edge(requestModelResponse forwardTo nodeFinish onTextMessage { true })
        edge(executeToolCalls forwardTo requestModelResponseWithToolResults)
        edge(requestModelResponseWithToolResults forwardTo executeToolCalls onToolCalls { true })
        edge(requestModelResponseWithToolResults forwardTo nodeFinish onTextMessage { true })
    }

fun interface AgentTurnRunner {
    suspend fun runTurn(
        ctx: TurnContext,
        message: SessionMessage,
        chat: ChatPlatformAdapter,
    ): AgentTurnResult
}

data class AgentTurnResult(
    val reply: ChatReply,
    val stats: AgentTurnStats,
)

data class AgentTurnStats(
    val profileName: String,
    val usage: AgentUsageStats,
)

data class AgentUsageStats(
    val toolCallCount: Int = 0,
    val inputTokenCount: Long = 0,
    val outputTokenCount: Long = 0,
)

private operator fun AgentUsageStats.plus(other: AgentUsageStats): AgentUsageStats =
    AgentUsageStats(
        toolCallCount = toolCallCount + other.toolCallCount,
        inputTokenCount = inputTokenCount + other.inputTokenCount,
        outputTokenCount = outputTokenCount + other.outputTokenCount,
    )
