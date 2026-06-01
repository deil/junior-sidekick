package com.github.uncomplexco.sidekick.application.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.adapters.spring.AgentConfigMeh
import com.github.uncomplexco.sidekick.application.context.PromptBuilder
import com.github.uncomplexco.sidekick.application.sessions.MessageAuthor
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import com.github.uncomplexco.sidekick.application.tools.SystemTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasRuntimeContext
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.slack.api.methods.MethodsClient
import org.springframework.stereotype.Component

data class TurnMessage(
    val user: MessageAuthor,
    val text: String,
)

@Component
class SidekickAgent(
    private val config: AgentConfig,
    private val configMeh: AgentConfigMeh,
    private val promptBuilder: PromptBuilder,
) {
    suspend fun runTurn(
        ctx: TurnContext,
        message: TurnMessage,
        slackClient: MethodsClient,
    ): String {
        val toolRegistry =
            ToolRegistry {
                tools(SystemTools())
                tools(
                    SlackCanvasTools(slackClient, ctx.sessionId).asTools(),
                )
            }

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
            )

        val input = promptBuilder.buildUserTurnPrompt(message, ctx)
        return agent.run(input)
    }
}
