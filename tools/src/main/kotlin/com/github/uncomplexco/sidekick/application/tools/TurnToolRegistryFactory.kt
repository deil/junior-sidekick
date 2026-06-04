package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.TurnToolRegistryFactory
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import com.github.uncomplexco.sidekick.application.tools.integrations.InternalFileExchangeTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DefaultTurnToolRegistryFactory(
    private val sharedContext: SharedContext,
    private val agentConfig: AgentConfig,
    @Value($$"${adapters.slack.bot.token}")
    private val slackBotToken: String,
    private val filePublisher: FilePublisher,
) : TurnToolRegistryFactory {
    override fun build(ctx: TurnContext): ToolRegistry =
        ToolRegistry {
            tools(SystemTools())
            tools(InternalFileExchangeTools(filePublisher, ctx, agentConfig.stateDirectoryPath()))
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.sessionId).asTools())
            tools(
                SlackReactionTools(
                    sharedContext.slackClient,
                    ctx,
                ).asTools(),
            )
            tools(SlackFileTools(ctx, slackBotToken, agentConfig.stateDirectoryPath()).asTools())
        }
}
