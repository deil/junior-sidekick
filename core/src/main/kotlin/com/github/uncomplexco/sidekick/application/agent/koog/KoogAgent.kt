package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.LlmProfile
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId

data class RunId(
    val conversationId: ConversationId?,
    val runId: String,
)

class KoogAgent(
    @PublishedApi internal val koogConfig: KoogConfig,
    @PublishedApi internal val agentId: String,
) {
    suspend inline fun <reified T> runSimple(
        runId: RunId,
        model: LlmProfile,
        crossinline systemPrompt: () -> String,
        crossinline input: () -> String,
    ): T {
        val prompt =
            prompt(
                id = "${runId.conversationId}-$agentId",
                params = koogConfig.openRouterParams(model, runId.conversationId),
            ) {
                system(systemPrompt())
                user(input())
            }
        val llm =
            LLModel(
                provider = LLMProvider.OpenRouter,
                id = model.model,
                capabilities = koogConfig.modelCapabilities(),
            )

        return koogConfig.openRouterExecutor().use { executor ->
            executor.executeStructured<T>(prompt, llm).getOrThrow().data
        }
    }

    suspend fun run(
        runId: RunId,
        tools: ToolRegistry,
        model: LlmProfile,
        systemPrompt: () -> String,
        input: () -> String,
    ): String {
        val koog =
            AIAgent(
                promptExecutor = koogConfig.openRouterExecutor(),
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
            )

        return koog.run(input(), "${runId.conversationId?.lockKey()}-$agentId")
    }
}
