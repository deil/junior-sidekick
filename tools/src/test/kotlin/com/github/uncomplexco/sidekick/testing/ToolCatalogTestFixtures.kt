package com.github.uncomplexco.sidekick.testing

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.adapters.files.LocalWorkspaceFilesystem
import com.github.uncomplexco.sidekick.adapters.git.WorkspaceGitFactory
import com.github.uncomplexco.sidekick.adapters.jgit.LocalGitCredentials
import com.github.uncomplexco.sidekick.adapters.jgit.LocalWorkspaceGit
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorConfig
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import com.github.uncomplexco.sidekick.application.agent.tools.ToolExecutionContext
import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.DiscordBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import com.github.uncomplexco.sidekick.application.tools.DefaultAgentToolCatalog
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.tools.mcp.McpAuthTools
import com.github.uncomplexco.sidekick.application.tools.mcp.McpOAuthService
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.subagents.Subagent
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentCatalog
import com.github.uncomplexco.sidekick.application.tools.subagents.SubagentRunner
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider
import com.slack.api.Slack
import java.nio.file.Files
import java.nio.file.Path

/** Shared real dependencies and test doubles for catalog and delegated execution tests. */
class ToolCatalogFixture(
    private val root: Path,
    bashEnabled: Boolean = true,
    providers: List<SidekickToolProvider> = emptyList(),
    mcp: McpToolsConfig = McpToolsConfig(),
) {
    val runner = CatalogSubagentRunner()
    val config = AgentConfig("Test", root.resolve("state").toString(), root.toString())
    private val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
    val catalog =
        DefaultAgentToolCatalog(
            SharedContext().apply { slackClient = Slack.getInstance().methods("unused") },
            config,
            StubSkillsCatalog(),
            mcp,
            McpAuthTools(mcp, McpOAuthService(config, mcp)),
            BashToolConfig().apply { enabled = bashEnabled },
            WorkspaceGitFactory { LocalWorkspaceGit(LocalGitCredentials(), it) },
            SandboxExecutorFactory(
                SandboxExecutorConfig().apply {
                    http.baseUrl = "http://localhost:1"
                    http.token = "unused"
                }
            ),
            WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem),
            store,
            AutomationFixture(config).service,
            WeeklyStatsService(store),
            runner,
            { SubagentCatalog(listOf(Subagent("general", "General-purpose subagent", "Prompt"))) },
            providers,
            NoOpProjectsRepository(),
        )

    fun execution(
        id: String = "parent",
        chat: ChatPlatformAdapter = CatalogSlackChat(),
        servers: List<ConnectedMcpServer> = emptyList(),
        headless: Boolean = false,
    ): ToolExecutionContext {
        val paths =
            VirtualPaths(
                directory("$id/session"),
                directory("skills"),
                directory("global"),
                directory("$id/work"),
                directory("project"),
            )
        val turn =
            TurnContext(
                ConversationContext(
                    ConversationId("C123", "1.23"),
                    paths,
                    ConversationHistory(emptyList(), emptyList(), false),
                    servers,
                ),
                id,
                "message-$id",
                emptyList(),
                AiModelProfile.NORMAL,
                headless = headless,
            )
        return ToolExecutionContext(turn, chat, ReplyAttachmentCollector(paths)) {}
    }

    private fun directory(path: String): Path = Files.createDirectories(root.resolve(path))
}

open class CatalogChat : ChatPlatformAdapter {
    override val botUsername = "USIDEKICK"
    val output = CatalogOutput()
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ) = files
}

class CatalogSlackChat : CatalogChat(), SlackBackedChatPlatformAdapter

class CatalogDiscordChat : CatalogChat(), DiscordBackedChatPlatformAdapter {
    override suspend fun loadChannelHistory(limit: Int, cursor: String?) =
        DiscordChannelHistoryPage("discord-channel", listOf(discordMessage()), null)

    override suspend fun loadThreadHistory(threadId: String, limit: Int, cursor: String?) =
        DiscordThreadHistoryPage("discord-channel", threadId, listOf(discordMessage()), null)

    private fun discordMessage() =
        DiscordChannelHistoryMessage(
            "message",
            "2026-10-29T12:00:00Z",
            "user",
            "requester",
            false,
            "History",
            null,
        )
}

class CatalogOutput : TurnLifecycle, ResponseDelivery {
    val progress = mutableListOf<String?>()
    val statusMessages = mutableListOf<Pair<String, String>>()
    val statusRefreshes = mutableMapOf<String, () -> String>()

    override fun begin() = Unit

    override fun progress(text: String?) {
        progress += text
    }

    override suspend fun reportStatus(text: String, key: String, refresh: (() -> String)?) {
        statusMessages += key to text
        statusRefreshes.remove(key)
        refresh?.let { statusRefreshes[key] = it }
    }

    override fun end() = Unit

    override suspend fun markProcessing(message: IncomingMessage) = Unit

    override suspend fun markCompleted(message: IncomingMessage) = Unit

    override suspend fun markFailed(message: IncomingMessage) = Unit

    override suspend fun deliver(response: AgentResponse, metadata: ResponseMetadata) =
        PublishedMessage("reply", 1)
}

class CatalogSubagentRunner : SubagentRunner {
    val runIds = mutableListOf<RunId>()
    val registries = mutableListOf<ToolRegistry>()

    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        conversation: ConversationContext,
    ): AgentRunResult<String> {
        runIds += runId
        registries += tools
        return AgentRunResult.Completed("Task completed", AgentUsageStats())
    }
}

class CatalogMcpServer(override val tools: List<ToolRegistration<Path>>) : ConnectedMcpServer {
    override val id = "test"

    override suspend fun close() = Unit
}

class ContextProbeTool(name: String, private val value: String) :
    Tool<JSONObject, String>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(name, "Return the bound context"),
    ) {
    override suspend fun execute(args: JSONObject): String = value
}
