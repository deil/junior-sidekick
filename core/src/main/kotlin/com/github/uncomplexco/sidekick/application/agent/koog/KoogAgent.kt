package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.parseResponseToStructuredResponse
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredRequest
import ai.koog.prompt.structure.StructuredRequestConfig
import ai.koog.prompt.structure.json.JsonStructure
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.LlmProfile
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

data class RunId(
    val conversationId: ConversationId?,
    val runId: String,
)

class KoogAgent(
    @PublishedApi internal val koogConfig: KoogConfig,
    @PublishedApi internal val agentId: String,
) {
    // This execution boundary returns ordinary failures; cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    suspend inline fun <reified T> runSimple(
        runId: RunId,
        model: LlmProfile,
        crossinline systemPrompt: () -> String? = { null },
        crossinline input: () -> String,
    ): AgentRunResult<T> {
        var usage = AgentUsageStats()
        return try {
            val prompt =
                prompt(
                    id = "${runId.conversationId}-$agentId",
                    params = koogConfig.openRouterParams(model, runId.conversationId),
                ) {
                    systemPrompt()?.let { system(it) }
                    user(input())
                }
            val llm =
                LLModel(
                    provider = LLMProvider.OpenRouter,
                    id = model.model,
                    capabilities = koogConfig.modelCapabilities(),
                )

            koogConfig.openRouterExecutor().use { executor ->
                log.info(
                    "Executing simple agent {} run {} for conversation {} with model {}",
                    agentId,
                    runId.runId,
                    runId.conversationId,
                    model.model,
                )
                val structure =
                    if (T::class == String::class) null
                    else
                        StructuredRequestConfig(
                            default = StructuredRequest.Manual(JsonStructure.create<T>())
                        )
                val response = executor.execute(structure?.updatePrompt(llm, prompt) ?: prompt, llm)
                usage =
                    AgentUsageStats(
                        inputTokenCount = response.metaInfo.inputTokensCount?.toLong() ?: 0,
                        outputTokenCount = response.metaInfo.outputTokensCount?.toLong() ?: 0,
                    )
                val output =
                    if (structure == null) {
                        response.textContent() as T
                    } else {
                        executor.parseResponseToStructuredResponse(response, structure, llm).data
                    }
                AgentRunResult.Completed(output, usage)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentRunResult.Failed(error, usage)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun run(
        runId: RunId,
        tools: ToolRegistry,
        model: LlmProfile,
        systemPrompt: () -> String,
        input: () -> String,
    ): AgentRunResult<String> {
        var usage = AgentUsageStats()
        return try {
            koogConfig.openRouterExecutor().use { executor ->
                val koog =
                    AIAgent(
                        promptExecutor = executor,
                        agentConfig =
                            AIAgentConfig(
                                model =
                                    LLModel(
                                        provider = LLMProvider.OpenRouter,
                                        id = model.model,
                                        capabilities = koogConfig.modelCapabilities(),
                                    ),
                                maxAgentIterations = koogConfig.maxAgentIterations,
                                prompt =
                                    prompt(
                                        id = "${runId.conversationId}-$agentId",
                                        params =
                                            koogConfig.openRouterParams(
                                                model,
                                                runId.conversationId,
                                            ),
                                    ) {
                                        system(systemPrompt())
                                    },
                            ),
                        toolRegistry = tools,
                    ) {
                        handleEvents {
                            onLLMCallCompleted { call ->
                                val meta = call.response?.metaInfo
                                usage +=
                                    AgentUsageStats(
                                        inputTokenCount = meta?.inputTokensCount?.toLong() ?: 0,
                                        outputTokenCount = meta?.outputTokensCount?.toLong() ?: 0,
                                    )
                            }
                            onToolCallStarting { usage += AgentUsageStats(toolCallCount = 1) }
                        }
                    }

                log.info(
                    "Executing agent {} run {} for conversation {} with model {}",
                    agentId,
                    runId.runId,
                    runId.conversationId,
                    model.model,
                )
                val output = koog.run(input(), "${runId.conversationId?.lockKey()}-$agentId")
                AgentRunResult.Completed(output, usage)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AgentRunResult.Failed(error, usage)
        }
    }

    companion object {
        @PublishedApi internal val log = LoggerFactory.getLogger(KoogAgent::class.java)
    }
}
