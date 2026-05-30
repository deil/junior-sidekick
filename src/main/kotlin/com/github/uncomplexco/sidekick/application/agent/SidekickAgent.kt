package com.github.uncomplexco.sidekick.application.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.adapters.spring.AgentConfigMeh
import com.github.uncomplexco.sidekick.application.formatUserMssage
import com.github.uncomplexco.sidekick.application.prompt.SystemPromptBuilder
import org.springframework.stereotype.Component

data class TurnMessage(
    val user: String,
    val text: String,
)

@Component
class SidekickAgent(
    private val config: AgentConfigMeh,
    private val promptBuilder: SystemPromptBuilder,
) {
    suspend fun runTurn(message: TurnMessage): String {
        val agent =
            AIAgent(
                promptExecutor = openRouterExecutor(config.openRouterApiKey),
                agentConfig =
                    AIAgentConfig(
                        prompt =
                            prompt(
                                id = "sidekick-base-prompt",
                                params = config.openRouterParams(),
                            ) {
                                system(promptBuilder.buildSystemPrompt())
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = config.model,
                                capabilities = config.modelCapabilities(),
                            ),
                        maxAgentIterations = 10,
                    ),
            )

        return agent.run(formatUserMssage(message.user, message.text))
    }
}
