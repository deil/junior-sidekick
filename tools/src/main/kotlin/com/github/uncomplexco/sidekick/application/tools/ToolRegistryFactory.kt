package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.adapters.sandbox.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
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
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTools
import com.github.uncomplexco.sidekick.application.tools.system.ConversationIntelligenceLevelTools
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.ToolRegistryFactory
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
    private val subagentRunner: SubagentRunner,
) : ToolRegistryFactory {
    override suspend fun build(
        ctx: TurnContext,
        chat: ChatPlatformAdapter,
    ): ToolRegistry =
        ToolRegistry {
            tools(SystemTools(chat = chat))
            tools(ConversationIntelligenceLevelTools(sharedContext.slackClient, ctx, conversationStateStore))
            if (bashToolConfig.enabled) {
                tools(
                    BashTools(
                        bashToolConfig,
                        ctx.conversation.virtualPaths,
                        sandboxExecutorFactory.create(),
                    ),
                )
            }
            tools(WebFetchTools(agentConfig.name))
            tools(TaskTools(subagentRunner, ctx, chat))
            tools(GitTools(gitToolConfig, ctx.conversation.virtualPaths))
            tools(WorkspaceFileTools(ctx.conversation.virtualPaths))
            tools(SkillTools(skills, ctx.conversation.virtualPaths, skillCatalogReloader))
            tools(
                InternalFileExchangeTools(
                    filePublisher,
                    ctx.conversation.virtualPaths,
                ),
            )
            tools(slackTools(sharedContext.slackClient, ctx))
            tools(McpStatusTools(ctx, mcpToolsConfig.servers).asTools())
            tools(mcpAuthTools.asTools(chat))
        }
}
