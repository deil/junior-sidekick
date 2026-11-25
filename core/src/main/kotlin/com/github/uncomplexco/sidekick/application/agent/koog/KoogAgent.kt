package com.github.uncomplexco.sidekick.application.agent.koog

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
import ai.koog.prompt.executor.clients.openrouter.models.ProviderPreferences
import ai.koog.prompt.executor.model.parseResponseToStructuredResponse
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
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
import kotlinx.serialization.json.JsonObject

data class RunId(
    val conversationId: ConversationId?,
    val runId: String,
)

class KoogAgent(
    @PublishedApi internal val koogConfig: KoogConfig,
    @PublishedApi internal val agentId: String,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend inline fun <reified T> runSimple(
        runId: RunId,
        model: LlmProfile,
        crossinline systemPrompt: () -> String? = { null },
        crossinline input: () -> String,
    ): AgentRunResult<T> {
        var usage = AgentUsageStats()
        val runLog = AgentRunLog(runId, agentId)
        return try {
            runLog.started(model.model)
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

            val result =
                koogConfig.openRouterExecutor().use { executor ->
                    val structure =
                        if (T::class == String::class) null
                        else
                            StructuredRequestConfig(
                                default = StructuredRequest.Manual(JsonStructure.create<T>())
                            )
                    val response =
                        executor.execute(structure?.updatePrompt(llm, prompt) ?: prompt, llm)
                    usage =
                        AgentUsageStats(
                            inputTokenCount = response.metaInfo.inputTokensCount?.toLong() ?: 0,
                            outputTokenCount = response.metaInfo.outputTokensCount?.toLong() ?: 0,
                        )
                    val output =
                        if (structure == null) {
                            response.textContent() as T
                        } else {
                            executor
                                .parseResponseToStructuredResponse(response, structure, llm)
                                .data
                        }
                    AgentRunResult.Completed(output, usage)
                }
            runLog.completed(usage)
            result
        } catch (cancelled: CancellationException) {
            runLog.cancelled()
            throw cancelled
        } catch (error: Exception) {
            runLog.failed(usage, error)
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
        outputSchema: JsonObject? = null,
    ): AgentRunResult<String> {
        var usage = AgentUsageStats()
        val runLog = AgentRunLog(runId, agentId)
        return try {
            val params = koogConfig.openRouterParams(model, runId.conversationId)
            val capabilities = koogConfig.modelCapabilities()
            val schema = outputSchema?.let { LLMParams.Schema.JSON.Standard("task_result", it) }
            val result =
                koogConfig.openRouterExecutor().use { executor ->
                    val koog =
                        AIAgent(
                            id = agentId,
                            promptExecutor = executor,
                            strategy =
                                toolLoop(
                                    schema?.let {
                                        params.copy(
                                            schema = it,
                                            provider =
                                                ProviderPreferences(
                                                    only = listOf(model.provider),
                                                    requireParameters = true,
                                                ),
                                        )
                                    }
                                ),
                            agentConfig =
                                AIAgentConfig(
                                    model =
                                        LLModel(
                                            provider = LLMProvider.OpenRouter,
                                            id = model.model,
                                            capabilities =
                                                if (schema == null) capabilities
                                                else capabilities + schema.capability,
                                        ),
                                    maxAgentIterations = koogConfig.maxAgentIterations,
                                    prompt =
                                        prompt(
                                            id = "${runId.conversationId}-$agentId",
                                            params = params,
                                        ) {
                                            system(systemPrompt())
                                            if (schema != null) {
                                                system(
                                                    "Gather the facts needed for this final output schema before answering:\n$outputSchema"
                                                )
                                            }
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
                                            outputTokenCount =
                                                meta?.outputTokensCount?.toLong() ?: 0,
                                        )
                                }
                                onToolCallStarting { usage += AgentUsageStats(toolCallCount = 1) }
                            }
                        }

                    runLog.started(model.model)
                    val output = koog.run(input(), "${runId.conversationId?.lockKey()}-$agentId")
                    AgentRunResult.Completed(output, usage)
                }
            runLog.completed(usage)
            result
        } catch (cancelled: CancellationException) {
            runLog.cancelled()
            throw cancelled
        } catch (error: Exception) {
            runLog.failed(usage, error)
            AgentRunResult.Failed(error, usage)
        }
    }
}

private fun toolLoop(finalParams: LLMParams?) =
    strategy<String, String>("single_run") {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()
        val finish =
            if (finalParams == null) nodeFinish
            else {
                val formatResult by
                    node<String, String> {
                        llm.writeSession {
                            changeLLMParams(finalParams)
                            appendPrompt {
                                user(
                                    "Return the completed task result in the requested JSON format using the conversation above."
                                )
                            }
                            requestLLMWithoutTools().textContent()
                        }
                    }
                edge(formatResult forwardTo nodeFinish)
                formatResult
            }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        // Koog takes the first matching edge. Text accompanying a tool call is not a final answer.
        for (reply in listOf(nodeCallLLM, nodeSendToolResult)) {
            edge(reply forwardTo nodeExecuteTool onToolCalls { true })
            edge(reply forwardTo finish onTextMessage { true })
        }
    }
