package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.adapters.sandbox.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.tools.bash.BashTools
import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import com.github.uncomplexco.sidekick.application.tools.integrations.InternalFileExchangeTools
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpStatusTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.mcp.McpAuthTools
import com.github.uncomplexco.sidekick.application.tools.skills.SkillTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackChannelTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackHistoryTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackUserTools
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.ToolRegistryFactory
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.chat.ReplyToMessage
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloader
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class DefaultToolRegistryFactory(
    private val sharedContext: SharedContext,
    private val agentConfig: AgentConfig,
    @Value($$"${adapters.slack.bot.token}")
    private val slackBotToken: String,
    private val filePublisher: FilePublisher,
    private val skills: SkillCatalogProvider,
    private val skillCatalogReloader: SkillCatalogReloader,
    private val mcpToolsConfig: McpToolsConfig,
    private val mcpAuthTools: McpAuthTools,
    private val bashToolConfig: BashToolConfig,
    private val sandboxExecutorFactory: SandboxExecutorFactory,
    private val conversationStateStore: ConversationStateStore,
    private val virtualPathsFactory: VirtualPathsFactory,
) : ToolRegistryFactory {
    override suspend fun build(
        ctx: TurnContext,
        activity: ChatActivityIndicator,
        reply: ReplyToMessage,
    ): ToolRegistry {
        val virtualPaths = virtualPathsFactory.forConversation(ctx.conversationId)

        return ToolRegistry {
            tools(SystemTools(activity = activity))
            tools(ConversationIntelligenceLevelTools(sharedContext.slackClient, ctx, conversationStateStore))
            if (bashToolConfig.enabled) {
                tools(
                    BashTools(
                        bashToolConfig,
                        virtualPaths,
                        sandboxExecutorFactory.create(),
                    ),
                )
            }
            tools(WebFetchTools(agentConfig.name))
            tools(WorkspaceFileTools(virtualPaths))
            tools(SkillTools(skills, virtualPaths, skillCatalogReloader))
            tools(
                InternalFileExchangeTools(
                    filePublisher,
                    virtualPaths,
                ),
            )
            tools(SlackCanvasTools(sharedContext.slackClient, ctx.conversationId).asTools())
            tools(SlackChannelTools(sharedContext.slackClient).asTools())
            tools(SlackHistoryTools(sharedContext.slackClient, ctx).asTools())
            tools(SlackUserTools(sharedContext.slackClient).asTools())
            tools(McpStatusTools(ctx, mcpToolsConfig.servers).asTools())
            tools(mcpAuthTools.asTools(reply))
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
                    virtualPaths,
                ).asTools(),
            )
        }
    }
}
