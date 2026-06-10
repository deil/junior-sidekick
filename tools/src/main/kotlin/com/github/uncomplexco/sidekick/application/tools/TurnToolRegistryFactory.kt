package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import com.github.uncomplexco.sidekick.application.tools.integrations.InternalFileExchangeTools
import com.github.uncomplexco.sidekick.application.tools.skills.ActivateSkillTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackHistoryTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.TurnToolRegistryFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DefaultTurnToolRegistryFactory(
    private val sharedContext: SharedContext,
    private val agentConfig: AgentConfig,
    @Value($$"${adapters.slack.bot.token}")
    private val slackBotToken: String,
    private val filePublisher: FilePublisher,
    private val skills: SkillCatalogProvider,
) : TurnToolRegistryFactory {
    override fun build(ctx: TurnContext): ToolRegistry =
        ToolRegistry {
            tools(SystemTools())
            tools(ActivateSkillTools(skills, agentConfig.skillsDirectoryPath()))
            tools(
                InternalFileExchangeTools(
                    filePublisher,
                    ctx,
                    agentConfig.stateDirectoryPath(),
                    agentConfig.skillsDirectoryPath(),
                ),
            )
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.conversationId).asTools())
            tools(SlackHistoryTools(sharedContext.slackClient).asTools())
            tools(
                SlackReactionTools(
                    sharedContext.slackClient,
                    ctx,
                ).asTools(),
            )
            tools(
                SlackFileTools(
                    ctx,
                    slackBotToken,
                    agentConfig.stateDirectoryPath(),
                    agentConfig.skillsDirectoryPath(),
                ).asTools(),
            )
        }
}
