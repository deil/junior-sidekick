package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ToolCalls
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentSteering
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.RecordedTurn
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.checkpoints.TurnCheckpointPersistence
import com.github.uncomplexco.sidekick.application.turn.resolveRequester
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
        userId: String?,
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
) : AgentTurnExecutor {
    override suspend fun execute(
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
        steer: AgentSteering,
    ): TurnExecutionResult {
        val ctx = turn.context
        val requester = resolveRequester(turn.messages)
        var usage = AgentUsageStats()

        val mcpServers =
            mcpServersRegistry.connect(
                ctx.conversation.conversationId,
                requester?.username,
                ctx.conversation.virtualPaths.workRoot,
            )
        val ctxWithMcp = ctx.copy(conversation = ctx.conversation.copy(mcpServers = mcpServers))
        val replyAttachments = ReplyAttachmentCollector(ctx.conversation.virtualPaths)

        try {
            val mcpToolRegistry =
                mcpServers.fold(ToolRegistry.EMPTY) { acc, server -> acc + server.toolRegistry }
            val baseTools =
                toolRegistryFactory.buildExecutionTools(ctxWithMcp, chat, replyAttachments) {
                    subagentUsage ->
                    usage += subagentUsage
                } + mcpToolRegistry
            val toolRegistry =
                baseTools +
                    toolRegistryFactory.buildOrchestrationTools(
                        toolRegistry = baseTools,
                        chat = chat,
                        ctx = ctxWithMcp,
                    )
            val aiModelProfile = koogConfig.profile(ctx.aiModelProfile)

            val seenMessages = turn.messages.map { it.id }.toMutableSet()
            val agent =
                AIAgent(
                    strategy =
                        sidekickStrategy(stopRequested = steer::stopRequested) {
                            val updated = steer.drain()
                            if (updated == null) emptyList()
                            else {
                                ctxWithMcp.lastMessageId = updated.context.lastMessageId
                                ctxWithMcp.sessionFiles = updated.context.sessionFiles
                                updated.messages
                                    .filter { seenMessages.add(it.id) }
                                    .map {
                                        turnPromptBuilder.buildSessionTurnPrompt(
                                            it,
                                            ctxWithMcp,
                                            requester = null,
                                            includeContext = false,
                                        )
                                    }
                            }
                        },
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
                                    system(
                                        systemPromptBuilder.buildSystemPrompt(
                                            config.botUsername!!,
                                            ctx.conversation.virtualPaths.projectRoot,
                                        )
                                    )
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
                                        llmCall.response?.metaInfo?.inputTokensCount?.toLong() ?: 0,
                                    outputTokenCount =
                                        llmCall.response?.metaInfo?.outputTokensCount?.toLong()
                                            ?: 0,
                                )
                        }

                        onToolCallStarting { toolCall ->
                            usage += AgentUsageStats(toolCallCount = 1)
                            log.debug("onToolCallStarting: ${toolCall.toolName}")
                        }

                        onToolCallFailed { toolCall ->
                            log.debug(
                                "onToolCallFailed: {} -> {}",
                                toolCall.toolName,
                                toolCall.message,
                            )
                        }

                        onToolCallCompleted { toolCall ->
                            log.debug(
                                "onToolCallCompleted: {} -> {}",
                                toolCall.toolName,
                                toolCall.toolResult,
                            )
                        }
                    }
                }

            val input = turnPromptBuilder.buildSessionTurnPrompts(turn.messages, ctxWithMcp)
            val outcome = agent.run(input, ctx.conversation.conversationId.lockKey())
            val stats = AgentStats(profileName = ctx.aiModelProfile.name.lowercase(), usage = usage)
            if (outcome == AgentOutcome.Stopped) {
                replyAttachments.clear()
                return TurnExecutionResult.Stopped(stats)
            }
            return TurnExecutionResult.Completed(
                AgentResponse(
                    (outcome as AgentOutcome.Completed).text,
                    replyAttachments.collected(),
                ),
                stats,
            )
        } catch (error: Exception) {
            replyAttachments.clear()
            throw error
        } finally {
            mcpServers.forEach { it.close() }
        }
    }
}

internal sealed interface AgentOutcome {
    data class Completed(val text: String) : AgentOutcome

    data object Stopped : AgentOutcome
}

internal fun sidekickStrategy(
    stopRequested: () -> Boolean = { false },
    steer: suspend () -> List<String> = { emptyList() },
) =
    strategy<List<String>, AgentOutcome>("sidekick") {
        val requestModelResponse by
            node<List<String>, Message.Assistant>("requestModelResponse") { messages ->
                llm.writeSession {
                    appendPrompt { messages.forEach { user(it) } }
                    requestLLM()
                }
            }
        val executeToolBatch by
            node<ToolCalls, List<String>?>("executeToolBatch") { batch ->
                val results =
                    if (stopRequested()) null
                    else
                        batch.toolCalls.map {
                            environment.executeTool(it).toMessagePart()
                        }
                val messages = if (results == null) emptyList() else steer()
                val stopped = results == null || stopRequested()
                val recordedResults =
                    if (stopped)
                        batch.toolCalls.map {
                            MessagePart.Tool.Result(
                                it.id,
                                it.tool,
                                "Tool execution was interrupted by the user.",
                                isError = true,
                            )
                        }
                    else checkNotNull(results)
                llm.writeSession {
                    appendPrompt {
                        user { recordedResults.forEach { toolResult(it) } }
                    }
                }
                if (stopped) null else messages
            }

        edge(nodeStart forwardTo requestModelResponse)
        edge(requestModelResponse forwardTo executeToolBatch onToolCalls { true })
        edge(
            (requestModelResponse forwardTo nodeFinish)
                .onTextMessage { true }
                .transformed { AgentOutcome.Completed(it) }
        )
        edge(
            (executeToolBatch forwardTo nodeFinish)
                .onCondition { it == null }
                .transformed { AgentOutcome.Stopped }
        )
        edge(
            (executeToolBatch forwardTo requestModelResponse)
                .onCondition { it != null }
                .transformed { checkNotNull(it) }
        )
    }
