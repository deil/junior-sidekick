package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.adapters.sandbox.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.tools.bash.BashTools
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.git.GitToolConfig
import com.github.uncomplexco.sidekick.application.tools.git.GitTools
import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import com.github.uncomplexco.sidekick.application.tools.integrations.InternalFileExchangeTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpAuthTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpStatusTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.skills.SkillTools
import com.github.uncomplexco.sidekick.application.tools.slack.slackTools
import com.github.uncomplexco.sidekick.application.tools.system.ConversationIntelligenceLevelTools
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.ToolRegistryFactory
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.chat.ReplyToMessage
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloader
import org.springframework.stereotype.Component

@Component
class DefaultToolRegistryFactory(
    private val sharedContext: SharedContext,
    private val agentConfig: AgentConfig,
    private val filePublisher: FilePublisher,
    private val skills: SkillCatalogProvider,
    private val skillCatalogReloader: SkillCatalogReloader,
    private val mcpToolsConfig: McpToolsConfig,
    private val mcpAuthTools: McpAuthTools,
    private val bashToolConfig: BashToolConfig,
    private val gitToolConfig: GitToolConfig,
    private val sandboxExecutorFactory: SandboxExecutorFactory,
    private val conversationStateStore: ConversationStateStore,
) : ToolRegistryFactory {
    override suspend fun build(
        ctx: TurnContext,
        activity: ChatActivityIndicator,
        reply: ReplyToMessage,
    ): ToolRegistry =
        ToolRegistry {
            tools(SystemTools(activity = activity))
            tools(ConversationIntelligenceLevelTools(sharedContext.slackClient, ctx, conversationStateStore))
            if (bashToolConfig.enabled) {
                tools(
                    BashTools(
                        bashToolConfig,
                        ctx.virtualPaths,
                        sandboxExecutorFactory.create(),
                    ),
                )
            }
            tools(WebFetchTools(agentConfig.name))
            tools(GitTools(gitToolConfig, ctx.virtualPaths))
            tools(WorkspaceFileTools(ctx.virtualPaths))
            tools(SkillTools(skills, ctx.virtualPaths, skillCatalogReloader))
            tools(
                InternalFileExchangeTools(
                    filePublisher,
                    ctx.virtualPaths,
                ),
            )
            tools(slackTools(sharedContext.slackClient, ctx))
            tools(McpStatusTools(ctx, mcpToolsConfig.servers).asTools())
            tools(mcpAuthTools.asTools(reply))
        }
}
