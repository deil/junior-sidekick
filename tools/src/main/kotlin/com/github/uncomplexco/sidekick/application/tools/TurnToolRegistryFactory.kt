package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import com.github.uncomplexco.sidekick.application.tools.integrations.InternalFileExchangeTools
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.mcp.ConfiguredMcpToolRegistryProvider
import com.github.uncomplexco.sidekick.application.tools.skills.SkillTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackChannelTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackHistoryTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.TurnToolRegistryFactory
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloader
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
    private val skillCatalogReloader: SkillCatalogReloader,
    private val mcpTools: ConfiguredMcpToolRegistryProvider,
) : TurnToolRegistryFactory {
    override suspend fun build(
        ctx: TurnContext,
        activity: ChatActivityIndicator,
    ): ToolRegistry =
        ToolRegistry {
            tools(SystemTools(activity = activity))
            tools(WorkspaceFileTools(agentConfig.globalDirectoryPath()))
            tools(SkillTools(skills, agentConfig.skillsDirectoryPath(), skillCatalogReloader))
            tools(
                InternalFileExchangeTools(
                    filePublisher,
                    ctx,
                    agentConfig.stateDirectoryPath(),
                    agentConfig.skillsDirectoryPath(),
                    agentConfig.globalDirectoryPath(),
                ),
            )
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.conversationId).asTools())
            tools(SlackChannelTools(sharedContext.slackClient).asTools())
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
                    agentConfig.globalDirectoryPath(),
                ).asTools(),
            )
        } + mcpTools.build()
}
