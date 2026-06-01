package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.SharedContext
import com.github.uncomplexco.sidekick.application.agent.TurnToolRegistryFactory
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import org.springframework.stereotype.Component

@Component
class DefaultTurnToolRegistryFactory(
    private val sharedContext: SharedContext,
) : TurnToolRegistryFactory {
    override fun build(ctx: TurnContext): ToolRegistry =
        ToolRegistry {
            tools(SystemTools())
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.sessionId).asTools())
            tools(
                SlackReactionTools(
                    sharedContext.slackClient,
                    ctx,
                ).asTools(),
            )
        }
}
