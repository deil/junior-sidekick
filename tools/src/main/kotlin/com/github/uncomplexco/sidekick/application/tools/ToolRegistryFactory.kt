package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.adapters.sandbox.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
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
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentCatalogProvider
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.application.tools.system.ConversationIntelligenceLevelTools
import com.github.uncomplexco.sidekick.application.tools.system.LoopFactory
import com.github.uncomplexco.sidekick.application.tools.system.LoopTools
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
    private val subagents: SubagentCatalogProvider,
    private val loopFactory: LoopFactory,
) : ToolRegistryFactory {
    override suspend fun buildExecutionTools(
        ctx: TurnContext,
        chat: ChatPlatformAdapter,
    ) = ToolRegistry {
        tools(SystemTools(chat = chat))

        tools(WorkspaceFileTools(ctx.conversation.virtualPaths))
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

        tools(SkillTools(skills, ctx.conversation.virtualPaths, skillCatalogReloader))

        tool(TaskTool(subagentRunner, ctx, chat, subagents.catalog().subagents))

        tools(GitTools(gitToolConfig, ctx.conversation.virtualPaths))
        tools(
            InternalFileExchangeTools(
                filePublisher,
                ctx.conversation.virtualPaths,
            ),
        )
        if (chat is SlackBackedChatPlatformAdapter) {
            tools(slackTools(sharedContext.slackClient, ctx))
        }
        tools(McpStatusTools(ctx, mcpToolsConfig.servers).asTools())
        tools(mcpAuthTools.asTools(chat))
    }

    override suspend fun buildOrchestrationTools(
        toolRegistry: ToolRegistry,
        chat: ChatPlatformAdapter,
        ctx: TurnContext,
    ) = ToolRegistry {
        if (chat is SlackBackedChatPlatformAdapter) {
            tools(ConversationIntelligenceLevelTools(sharedContext.slackClient, ctx, conversationStateStore))
        }

        tools(
            LoopTools(
                factory = loopFactory,
                chat = chat,
                toolRegistry = toolRegistry,
                ctx = ctx,
            ),
        )
    }
}
