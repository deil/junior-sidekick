package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.koog.McpServersRegistry
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import com.github.uncomplexco.sidekick.application.agent.onProjectCreated
import com.github.uncomplexco.sidekick.application.agent.onWorkCreated
import com.github.uncomplexco.sidekick.application.agent.tools.AgentToolCatalog
import com.github.uncomplexco.sidekick.application.agent.tools.ToolExecutionContext
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import java.nio.file.Files
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class HeadlessTaskRunner(
    private val config: AgentConfig,
    private val tools: AgentToolCatalog,
    private val subagents: SubagentRunner,
    private val filesystems: WorkspaceFilesystemFactory,
    private val mcp: McpServersRegistry,
) : IsolatedSubagentRunner {
    private val log = LoggerFactory.getLogger(HeadlessTaskRunner::class.java)

    override suspend fun runIsolated(
        runId: String,
        subagent: Subagent,
        prompt: String,
    ): AgentRunResult<String> {
        val conversationId = ConversationId(runId, runId)
        val paths = createWorkspace(runId)
        val filesystem = filesystems.create(paths)
        onWorkCreated(config, paths.workRoot)
        onProjectCreated(config, runId, filesystem)
        log.info("Initialized workspace for API task projectId={}", runId)

        val attachments = ReplyAttachmentCollector(runId, filesystem, paths.sessionRoot)
        val servers = mcp.connect(conversationId, userId = null, workRoot = paths.workRoot)

        try {
            val conversation =
                ConversationContext(
                    conversationId,
                    paths,
                    ConversationHistory(emptyList(), emptyList(), hasKoogMessages = false),
                    servers,
                )
            val turn =
                TurnContext(
                    conversation,
                    turnId = runId,
                    lastMessageId = runId,
                    sessionFiles = emptyList(),
                    aiModelProfile = AiModelProfile.NORMAL,
                    headless = true,
                )
            val registry =
                tools.bindDelegate(ToolExecutionContext(turn, HeadlessChat, attachments) {})
            return subagents.run(
                runId,
                RunId(conversationId, runId),
                subagent.name,
                prompt,
                registry,
                conversation,
            )
        } finally {
            withContext(NonCancellable) { servers.forEach { it.close() } }
        }
    }

    private fun createWorkspace(projectId: String): VirtualPaths {
        val layout = config.workspaceLayout()
        val root = Files.createDirectory(layout.taskWorkspacesDirectoryPath().resolve(projectId))
        return VirtualPaths(
            sessionRoot = Files.createDirectory(root.resolve("session")),
            skillsRoot = layout.extensionsRepositoryDirectoryPath(),
            globalRoot = layout.knowledgeRepositoryDirectoryPath(),
            workRoot = Files.createDirectory(root.resolve("work")),
            projectRoot = Files.createDirectory(root.resolve("project")),
        )
    }

    private object HeadlessChat : ChatPlatformAdapter, TurnLifecycle, ResponseDelivery {
        override val botUsername = "sidekick"
        override val lifecycle: TurnLifecycle
            get() = this

        override val delivery: ResponseDelivery
            get() = this

        override fun begin() = Unit

        override fun progress(text: String?) = Unit

        override fun end() = Unit

        override suspend fun markProcessing(message: IncomingMessage) = Unit

        override suspend fun markCompleted(message: IncomingMessage) = Unit

        override suspend fun markFailed(message: IncomingMessage) = Unit

        override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
            emptyList()

        override suspend fun ingestFiles(
            conversationId: ConversationId,
            files: List<IncomingChatFile>,
        ) = emptyList<IncomingChatFile>()

        override suspend fun deliver(response: AgentResponse, metadata: ResponseMetadata): Nothing =
            error("Headless tasks cannot post to chat")
    }
}
