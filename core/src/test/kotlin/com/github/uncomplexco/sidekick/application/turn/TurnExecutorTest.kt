package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.ReplyAttachment
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnResultHandler
import com.github.uncomplexco.sidekick.application.chat.TurnStats
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnResult
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnStats
import com.github.uncomplexco.sidekick.application.turn.koog.AgentUsageStats
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TurnExecutorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `scheduled turn runs directly in isolated session`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val seenConversationIds = mutableListOf<ConversationId>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent =
                        AgentTurnRunner { ctx, message, _ ->
                            seenConversationIds += ctx.conversation.conversationId
                            assertEquals("Run the report", message.text)
                            AgentTurnResult(ChatReply("done"), AgentTurnStats("normal", AgentUsageStats()))
                        },
                    skills = { SkillCatalog(emptyList()) },
                )
            val job = ScheduledJob(32, "report", null, "0 9 * * *", "UTC", "Run the report")
            val first = ConversationId("C123", "scheduled_32_first")
            val second = ConversationId("C123", "scheduled_32_second")

            // Act
            executor.runScheduled(first, job, chat(replies, mutableListOf()), startedAtMs = 1_000)
            executor.runScheduled(second, job, chat(replies, mutableListOf()), startedAtMs = 2_000)

            // Assert
            assertEquals(listOf(first, second), seenConversationIds)
            assertEquals(listOf("done", "done"), replies)
            assertEquals(2, store.load(first).messages.size)
            assertEquals(2, store.load(second).messages.size)
            assertEquals(
                "scheduled-job-32",
                store.load(first).messages.single { it.role == SessionMessageRole.USER }.author?.username,
            )
        }

    @Test
    fun `posts temporary failure reply and marks message skipped when agent fails`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val lifecycle = mutableListOf<String>()
            val statusLines = mutableListOf<String?>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent = AgentTurnRunner { _, _, _ -> error("OpenRouter API error: 429 Too Many Requests") },
                    skills = { SkillCatalog(emptyList()) },
                )
            val chat = chat(replies, lifecycle, statusLines)
            val message =
                InboundMessage(
                    id = "1700000000.000",
                    createdAtMs = 1,
                    sender = MessageAuthor(username = "U123", fullName = "User"),
                    text = "<@USIDEKICK> help",
                    type = ChatMessageType.EXPLICIT_MENTION,
                )

            // Act
            executor.run(ChatConversationId("C123"), listOf(message), chat)

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
    fun `delivers reply attachments and removes staged files`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val stagedFile = dir.resolve("staged/report.csv")
            Files.createDirectories(stagedFile.parent)
            Files.writeString(stagedFile, "report\n")
            val attachment = ReplyAttachment(stagedFile, "report.csv", "text/csv", Files.size(stagedFile))
            val delivered = mutableListOf<ChatReply>()
            val deliveredStats = mutableListOf<TurnStats?>()
            val lifecycle = mutableListOf<String>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent =
                        AgentTurnRunner { _, _, _ ->
                            AgentTurnResult(
                                reply = ChatReply("Attached the report.", listOf(attachment)),
                                stats =
                                    AgentTurnStats(
                                        profileName = "normal",
                                        usage = AgentUsageStats(inputTokenCount = 1_200, outputTokenCount = 345),
                                    ),
                            )
                        },
                    skills = { SkillCatalog(emptyList()) },
                )
            val chat =
                object : ChatPlatformAdapter {
                    override val botUsername = "USIDEKICK"
                    override val resultHandler =
                        object : TurnResultHandler {
                            override fun start() = Unit

                            override fun `continue`(text: String?) = Unit

                            override fun endTurn() = Unit

                            override suspend fun markProcessing(message: InboundMessage) {
                                lifecycle += "processing"
                            }

                            override suspend fun postReply(
                                reply: ChatReply,
                                stats: TurnStats?,
                            ): ReplyResult {
                                delivered += reply
                                deliveredStats += stats
                                return ReplyResult("reply", 1)
                            }

                            override suspend fun markCompleted(message: InboundMessage) {
                                lifecycle += "completed"
                            }

                            override suspend fun markFailed(message: InboundMessage) {
                                lifecycle += "failed"
                            }
                        }

                    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

                    override suspend fun ingestFiles(
                        conversationId: ConversationId,
                        files: List<IncomingChatFile>,
                    ): List<IncomingChatFile> = files
                }
            val message =
                InboundMessage(
                    id = "1700000000.000",
                    createdAtMs = 1,
                    sender = MessageAuthor(username = "U123", fullName = "User"),
                    text = "<@USIDEKICK> send the report",
                    type = ChatMessageType.EXPLICIT_MENTION,
                )

            // Act
            executor.run(ChatConversationId("C123"), listOf(message), chat)

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
    fun `does not mark skipped messages as processing`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val lifecycle = mutableListOf<String>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent = AgentTurnRunner { _, _, _ -> error("agent should not run") },
                    skills = { SkillCatalog(emptyList()) },
                )
            val message =
                InboundMessage(
                    id = "1700000000.001",
                    createdAtMs = 1,
                    sender = MessageAuthor(username = "U123", fullName = "User"),
                    text = "thanks",
                    type = ChatMessageType.ASSISTANT_MESSAGE,
                )

            // Act
            executor.run(ChatConversationId("D123", "1700000000.000"), listOf(message), chat(mutableListOf(), lifecycle))

            // Assert
            assertEquals(emptyList(), lifecycle)
        }

    @Test
    fun `posts unsubscribe notification without marking message as processing`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val lifecycle = mutableListOf<String>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent = AgentTurnRunner { _, _, _ -> error("agent should not run") },
                    skills = { SkillCatalog(emptyList()) },
                )
            val message =
                InboundMessage(
                    id = "1700000000.002",
                    createdAtMs = 1,
                    sender = MessageAuthor(username = "U123", fullName = "User"),
                    text = "<@USIDEKICK> unsubscribe",
                    type = ChatMessageType.EXPLICIT_MENTION,
                )

            // Act
            executor.run(ChatConversationId("C123"), listOf(message), chat(replies, lifecycle))

            // Assert
            assertEquals(listOf("Unsubscribed. Mention me to resume"), replies)
            assertEquals(emptyList(), lifecycle)
        }

    private fun conversationManager(
        config: AgentConfig,
        store: FilesystemConversationStateStore,
    ): ConversationManager =
        ConversationManager(
            store,
            VirtualPathsFactory(config),
            SessionContextCompactor(
                summarizer = { _, _, messages -> "summary for ${messages.size} messages" },
            ),
        )

    private fun replyDecisionService(): ReplyDecisionService =
        ReplyDecisionService(
            SimpleReplyDecisionClassifier(),
            LlmReplyDecisionClassifier(koogConfig()) { _, _ -> error("classifier should not run") },
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
            override val resultHandler =
                object : TurnResultHandler {
                    override fun start() = Unit

                    override fun `continue`(text: String?) = Unit

                    override fun endTurn() = Unit

                    override suspend fun markProcessing(message: InboundMessage) {
                        lifecycle += "processing"
                    }

                    override suspend fun postReply(
                        reply: ChatReply,
                        stats: TurnStats?,
                    ): ReplyResult {
                        replies += reply.text
                        statusLines += reply.statusLine
                        return ReplyResult("reply-${replies.size}", replies.size.toLong())
                    }

                    override suspend fun markCompleted(message: InboundMessage) {
                        lifecycle += "completed"
                    }

                    override suspend fun markFailed(message: InboundMessage) {
                        lifecycle += "failed"
                    }
                }

            override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

            override suspend fun ingestFiles(
                conversationId: ConversationId,
                files: List<IncomingChatFile>,
            ): List<IncomingChatFile> = files

        }
}
