package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.adapters.git.WorkspaceGitFactory
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillsCatalog
import com.github.uncomplexco.sidekick.application.agent.tools.AgentToolCatalog
import com.github.uncomplexco.sidekick.application.agent.tools.ToolAccess
import com.github.uncomplexco.sidekick.application.agent.tools.ToolExecutionContext
import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import com.github.uncomplexco.sidekick.application.chat.DiscordBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import com.github.uncomplexco.sidekick.application.core.AutomationService
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import com.github.uncomplexco.sidekick.application.tools.automations.AutomationTools
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.tools.bash.BashTools
import com.github.uncomplexco.sidekick.application.tools.discord.discordTools
import com.github.uncomplexco.sidekick.application.tools.files.ReplyAttachmentTools
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.git.GitTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpAuthTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpStatusTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.skills.SkillTools
import com.github.uncomplexco.sidekick.application.tools.skills.SkillsManagementTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackCanvasTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackChannelTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackFileTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackHistoryTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackReactionTools
import com.github.uncomplexco.sidekick.application.tools.slack.SlackUserTools
import com.github.uncomplexco.sidekick.application.tools.stats.StatsTools
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentCatalogProvider
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.application.tools.system.ConversationIntelligenceLevelTools
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import com.github.uncomplexco.sidekick.application.tools.todos.TodoTool
import com.github.uncomplexco.sidekick.application.tools.web.WebFetchTools
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import com.github.uncomplexco.sidekick.tools.SidekickToolContext
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private const val MAX_DELEGATION_DEPTH = 2

@Component
class DefaultAgentToolCatalog(
    sharedContext: SharedContext,
    agentConfig: AgentConfig,
    skills: SkillsCatalog,
    mcpToolsConfig: McpToolsConfig,
    mcpAuthTools: McpAuthTools,
    bashToolConfig: BashToolConfig,
    workspaceGitFactory: WorkspaceGitFactory,
    sandboxExecutorFactory: SandboxExecutorFactory,
    workspaceFilesystemFactory: WorkspaceFilesystemFactory,
    conversationStateStore: ConversationStateStore,
    automations: AutomationService,
    weeklyStats: WeeklyStatsService,
    subagentRunner: SubagentRunner,
    subagents: SubagentCatalogProvider,
    sidekickToolProviders: List<SidekickToolProvider>,
    projects: ProjectsRepository,
) : AgentToolCatalog {
    private val registrations: List<ToolRegistration<ToolExecutionContext>> =
        listOf<ToolRegistration<ToolExecutionContext>>(
            ToolRegistration("system", ToolAccess.SHARED) { SystemTools().asTools() },
            ToolRegistration("stats", ToolAccess.SHARED) { StatsTools(weeklyStats).asTools() },
            ToolRegistration("workspace", ToolAccess.SHARED) {
                WorkspaceFileTools(
                        it.turn.conversation.conversationId.channelId,
                        workspaceFilesystemFactory.create(it.turn.conversation.virtualPaths),
                    )
                    .asTools()
            },
            ToolRegistration("bash", ToolAccess.SHARED) {
                if (bashToolConfig.enabled) {
                    BashTools(
                            bashToolConfig,
                            it.turn.conversation.conversationId.channelId,
                            sandboxExecutorFactory.create(it.turn.conversation.virtualPaths),
                        )
                        .asTools()
                } else emptyList()
            },
            ToolRegistration("web", ToolAccess.SHARED) {
                WebFetchTools(agentConfig.name).asTools()
            },
            ToolRegistration("skills", ToolAccess.SHARED) {
                SkillTools(skills, it.turn.conversation.virtualPaths, it.turn.conversation.project)
                    .asTools()
            },
            ToolRegistration("skills-reload", ToolAccess.ORCHESTRATOR_ONLY) {
                SkillsManagementTools(skills).asTools()
            },
            ToolRegistration("git", ToolAccess.SHARED) {
                GitTools(
                        it.turn.conversation.conversationId.channelId,
                        workspaceGitFactory.create(it.turn.conversation.virtualPaths),
                    )
                    .asTools()
            },
            ToolRegistration("discord-history", ToolAccess.SHARED) {
                val chat = it.chat
                if (chat is DiscordBackedChatPlatformAdapter) discordTools(chat) else emptyList()
            },
            ToolRegistration("slack-read", ToolAccess.SHARED) {
                if (it.chat is SlackBackedChatPlatformAdapter) {
                    SlackChannelTools(sharedContext.slackClient).asTools() +
                        SlackHistoryTools(sharedContext.slackClient, it.turn, projects)
                            .asLlmTools() +
                        SlackUserTools(sharedContext.slackClient).asLlmTools() +
                        SlackFileTools(
                                it.turn,
                                workspaceFilesystemFactory.create(
                                    it.turn.conversation.virtualPaths
                                ),
                            )
                            .asTools()
                } else emptyList()
            },
            ToolRegistration("mcp-status", ToolAccess.SHARED) {
                McpStatusTools(it.turn, mcpToolsConfig.servers).asTools()
            },
            ToolRegistration("task", ToolAccess.SHARED) { execution ->
                if (execution.delegationDepth >= MAX_DELEGATION_DEPTH)
                    return@ToolRegistration emptyList()

                listOf(
                    TaskTool(
                        subagentRunner,
                        execution.turn,
                        execution.chat.lifecycle,
                        subagents.catalog().subagents,
                        execution.onSubagentCompleted,
                        bindTools = { child ->
                            bindDelegate(execution.copy(turn = child))
                        },
                    )
                )
            },
            ToolRegistration("slack-output", ToolAccess.ORCHESTRATOR_ONLY) {
                if (it.chat is SlackBackedChatPlatformAdapter) {
                    ReplyAttachmentTools(it.replyAttachments).asTools() +
                        SlackCanvasTools(
                                sharedContext.slackClient,
                                it.turn.conversation.conversationId,
                            )
                            .asTools() +
                        SlackReactionTools(sharedContext.slackClient, it.turn).asTools()
                } else emptyList()
            },
            ToolRegistration("automations", ToolAccess.ORCHESTRATOR_ONLY) {
                if (it.chat is SlackBackedChatPlatformAdapter) {
                    AutomationTools(it.turn.conversation.conversationId.channelId, automations)
                        .asLlmTools()
                } else emptyList()
            },
            ToolRegistration("session-settings", ToolAccess.ORCHESTRATOR_ONLY) {
                if (it.chat is SlackBackedChatPlatformAdapter) {
                    ConversationIntelligenceLevelTools(
                            sharedContext.slackClient,
                            it.turn,
                            conversationStateStore,
                        )
                        .asTools()
                } else emptyList()
            },
            ToolRegistration("mcp-auth", ToolAccess.ORCHESTRATOR_ONLY) {
                mcpAuthTools.asTools(it.chat.delivery)
            },
        ) +
            sidekickToolProviders.flatMap { provider ->
                provider.registrations().map { registration ->
                    ToolRegistration(registration.id, registration.access) { execution ->
                        registration.bind(
                            SidekickToolContext { path ->
                                Path.of(
                                        parseVirtualPath(
                                            path,
                                            execution.turn.conversation.virtualPaths,
                                        )
                                    )
                                    .toAbsolutePath()
                                    .normalize()
                            }
                        )
                    }
                }
            }

    override suspend fun bindOrchestrator(context: ToolExecutionContext): ToolRegistry =
        bind(context, orchestrator = true)

    override suspend fun bindDelegate(context: ToolExecutionContext): ToolRegistry =
        bind(context.copy(delegationDepth = context.delegationDepth + 1), orchestrator = false)

    private fun bind(context: ToolExecutionContext, orchestrator: Boolean): ToolRegistry {
        val mcpRegistrations =
            context.turn.conversation.mcpServers.flatMap { server ->
                server.tools.map { registration ->
                    ToolRegistration<ToolExecutionContext>(registration.id, registration.access) {
                        registration.bind(it.turn.conversation.virtualPaths.workRoot)
                    }
                }
            }
        val tools =
            (registrations + mcpRegistrations)
                .filter { orchestrator || it.access == ToolAccess.SHARED }
                .flatMap { it.bind(context) } +
                TodoTool(
                    if (orchestrator && !context.turn.headless) context.chat.lifecycle else null
                )
        val registry = ToolRegistry { tools(tools) }
        log.info(
            "Bound {} tools for {} execution {}",
            tools.size,
            if (orchestrator) "orchestrator" else "delegate",
            context.turn.turnId,
        )
        return registry
    }

    companion object {
        private val log = LoggerFactory.getLogger(DefaultAgentToolCatalog::class.java)
    }
}
