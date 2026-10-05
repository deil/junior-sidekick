package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TurnCoordinatorTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `scheduled turn runs directly in isolated session`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val seenConversationIds = mutableListOf<ConversationId>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { turn, _ ->
                        seenConversationIds += turn.context.conversation.conversationId
                        assertEquals("Run the report", turn.messages.single().text)
                        TurnExecutionResult(
                            AgentResponse("done"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val job = ScheduledJob(32, "report", null, "0 9 * * *", "UTC", "Run the report")
        val first = ConversationId("C123", "scheduled_32_first")
        val second = ConversationId("C123", "scheduled_32_second")

        // Act
        executor.run(
            TurnTrigger.Scheduled(
                first,
                job,
                chat(replies, mutableListOf()),
                startedAtMs = 1_000,
            )
        )
        executor.run(
            TurnTrigger.Scheduled(
                second,
                job,
                chat(replies, mutableListOf()),
                startedAtMs = 2_000,
            )
        )

        // Assert
        assertEquals(listOf(first, second), seenConversationIds)
        assertEquals(listOf("done", "done"), replies)
        assertEquals(2, store.load(first).messages.size)
        assertEquals(2, store.load(second).messages.size)
        assertEquals(
            "scheduled-job-32",
            store
                .load(first)
                .messages
                .single { it.role == SessionMessageRole.USER }
                .author
                ?.username,
        )
    }

    @Test
    fun `posts temporary failure reply and marks message skipped when agent fails`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        val statusLines = mutableListOf<String?>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { _, _ ->
                        error("OpenRouter API error: 429 Too Many Requests")
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val chat = chat(replies, lifecycle, statusLines)
        val message =
            IncomingMessage(
                id = "1700000000.000",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> help",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat)

        // Assert
        assertEquals(1, replies.size)
        assertEquals(":warning: OpenRouter API error: 429 Too Many Requests", replies.single())
        assertEquals(listOf<String?>("`[runtime failure]`"), statusLines)
        val state = store.load(ConversationId("C123", "1700000000.000"))
        val savedMessage = state.messages.single { it.id == message.id }
        assertEquals("AGENT_FAILURE", savedMessage.skippedReason)
        assertFalse(savedMessage.replied == true)
        assertEquals(emptyList(), state.messages.filter { it.role == SessionMessageRole.ASSISTANT })
        assertEquals(listOf("processing", "failed"), lifecycle)
    }

    @Test
    fun `delivers reply attachments and removes staged files`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val stagedFile = dir.resolve("staged/report.csv")
        Files.createDirectories(stagedFile.parent)
        Files.writeString(stagedFile, "report\n")
        val attachment =
            ReplyAttachment(stagedFile, "report.csv", "text/csv", Files.size(stagedFile))
        val delivered = mutableListOf<AgentResponse>()
        val deliveredStats = mutableListOf<TurnStats?>()
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { _, _ ->
                        TurnExecutionResult(
                            response = AgentResponse("Attached the report.", listOf(attachment)),
                            stats =
                                AgentStats(
                                    profileName = "normal",
                                    usage =
                                        AgentUsageStats(
                                            inputTokenCount = 1_200,
                                            outputTokenCount = 345,
                                        ),
                                ),
                        )
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val chat =
            object : ChatPlatformAdapter {
                override val botUsername = "USIDEKICK"
                private val output =
                    object : TurnLifecycle, ResponseDelivery {
                        override fun begin() = Unit

                        override fun progress(text: String?) = Unit

                        override fun end() = Unit

                        override suspend fun markProcessing(message: IncomingMessage) {
                            lifecycle += "processing"
                        }

                        override suspend fun deliver(
                            response: AgentResponse,
                            metadata: ResponseMetadata,
                        ): PublishedMessage {
                            delivered += response
                            deliveredStats += (metadata as? ResponseMetadata.Turn)?.stats
                            return PublishedMessage("reply", 1)
                        }

                        override suspend fun markCompleted(message: IncomingMessage) {
                            lifecycle += "completed"
                        }

                        override suspend fun markFailed(message: IncomingMessage) {
                            lifecycle += "failed"
                        }
                    }
                override val lifecycle: TurnLifecycle = output
                override val delivery: ResponseDelivery = output

                override suspend fun loadHistory(
                    conversationId: ConversationId
                ): List<ChatMessage> = emptyList()

                override suspend fun ingestFiles(
                    conversationId: ConversationId,
                    files: List<IncomingChatFile>,
                ): List<IncomingChatFile> = files
            }
        val message =
            IncomingMessage(
                id = "1700000000.000",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> send the report",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat)

        // Assert
        assertEquals(listOf(attachment), delivered.single().attachments)
        assertEquals(1_200, deliveredStats.single()?.inputTokenCount)
        assertEquals(345, deliveredStats.single()?.outputTokenCount)
        assertFalse(Files.exists(stagedFile))
        val state = store.load(ConversationId("C123", "1700000000.000"))
        assertEquals(1_200, state.stats.consumedInputTokens)
        assertEquals(345, state.stats.consumedOutputTokens)
        assertEquals(listOf("processing", "completed"), lifecycle)
        assertEquals(
            emptyList(),
            state.messages.single { it.role == SessionMessageRole.ASSISTANT }.fileIds,
        )
    }

    @Test
    fun `does not mark skipped messages as processing`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent = AgentTurnExecutor { _, _ -> error("agent should not run") },
                skills = { SkillCatalog(emptyList()) },
            )
        val message =
            IncomingMessage(
                id = "1700000000.001",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "thanks",
                type = IncomingMessageType.ASSISTANT_MESSAGE,
            )

        // Act
        executor.runIncoming(
            ChatConversationId("D123", "1700000000.000"),
            message,
            chat(mutableListOf(), lifecycle),
        )

        // Assert
        assertEquals(emptyList(), lifecycle)
    }

    @Test
    fun `posts unsubscribe notification without marking message as processing`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent = AgentTurnExecutor { _, _ -> error("agent should not run") },
                skills = { SkillCatalog(emptyList()) },
            )
        val message =
            IncomingMessage(
                id = "1700000000.002",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> unsubscribe",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat(replies, lifecycle))

        // Assert
        assertEquals(listOf("Unsubscribed. Mention me to resume"), replies)
        assertEquals(emptyList(), lifecycle)
    }

    private fun conversationManager(
        config: AgentConfig,
        store: FilesystemConversationStateStore,
    ): SessionManager =
        SessionManager(
            store,
            VirtualPathsFactory(config, ChatPlatform.SLACK),
            SessionContextCompactor(
                summarizer = { _, _, messages -> "summary for ${messages.size} messages" }
            ),
        )

    private fun config(): AgentConfig =
        AgentConfig(
                "Sidekick",
                dir.resolve("state").toString(),
                dir.resolve("workspace").toString(),
            )
            .also { it.botUsername = "USIDEKICK" }

    private fun participation(config: AgentConfig): TurnParticipation =
        TurnParticipation(
            config,
            DeterministicParticipationClassifier(),
            LlmParticipationClassifier(koogConfig()) { _, _ -> error("classifier should not run") },
        )

    private fun koogConfig(): KoogConfig =
        KoogConfig(
            openRouterApiKey = "test-key",
            openRouterAppTitle = "Sidekick",
            openRouterAppUrl = "",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.5",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            imageModel = "image-model",
            maxAgentIterations = 50,
        )

    private fun chat(
        replies: MutableList<String>,
        lifecycle: MutableList<String>,
        statusLines: MutableList<String?> = mutableListOf(),
    ): ChatPlatformAdapter =
        object : ChatPlatformAdapter {
            override val botUsername = "USIDEKICK"
            private val output =
                object : TurnLifecycle, ResponseDelivery {
                    override fun begin() = Unit

                    override fun progress(text: String?) = Unit

                    override fun end() = Unit

                    override suspend fun markProcessing(message: IncomingMessage) {
                        lifecycle += "processing"
                    }

                    override suspend fun deliver(
                        response: AgentResponse,
                        metadata: ResponseMetadata,
                    ): PublishedMessage {
                        replies += response.text
                        statusLines +=
                            if (metadata == ResponseMetadata.RuntimeFailure) {
                                "`[runtime failure]`"
                            } else {
                                null
                            }
                        return PublishedMessage("reply-${replies.size}", replies.size.toLong())
                    }

                    override suspend fun markCompleted(message: IncomingMessage) {
                        lifecycle += "completed"
                    }

                    override suspend fun markFailed(message: IncomingMessage) {
                        lifecycle += "failed"
                    }
                }
            override val lifecycle: TurnLifecycle = output
            override val delivery: ResponseDelivery = output

            override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
                emptyList()

            override suspend fun ingestFiles(
                conversationId: ConversationId,
                files: List<IncomingChatFile>,
            ): List<IncomingChatFile> = files
        }

    private suspend fun TurnCoordinator.runIncoming(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        run(
            TurnTrigger.Incoming(
                IncomingBatch(
                    conversationId = conversationId,
                    messages = listOf(message),
                ),
                chat,
            )
        )
    }
}
