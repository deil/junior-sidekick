package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.SharedContext
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.TurnToolRegistryFactory
import com.github.uncomplexco.sidekick.application.config.AgentConfigMeh
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DefaultTurnToolRegistryFactory(
    private val sharedContext: SharedContext,
    private val agentConfig: AgentConfigMeh,
    @Value($$"${adapters.slack.bot.token}")
    private val slackBotToken: String,
    private val filePublisher: FilePublisher,
) : TurnToolRegistryFactory {
    override fun build(ctx: TurnContext): ToolRegistry =
        ToolRegistry {
            tools(SystemTools())
            tools(InternalFileExchangeTools(filePublisher))
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.sessionId).asTools())
            tools(
                SlackReactionTools(
                    sharedContext.slackClient,
                    ctx,
                ).asTools(),
            )
            tools(SlackFileTools(ctx, slackBotToken, agentConfig.workingDirectoryPath().resolve("tmp")).asTools())
        }
}
