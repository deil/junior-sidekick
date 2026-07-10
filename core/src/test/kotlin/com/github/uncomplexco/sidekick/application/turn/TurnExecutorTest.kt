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
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
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
    fun `posts temporary failure reply and marks message skipped when agent fails`() =
        runBlocking {
            // Arrange
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent = AgentTurnRunner { _, _, _ -> error("OpenRouter API error: 429 Too Many Requests") },
                    skills = { SkillCatalog(emptyList()) },
                )
            val chat = chat(replies)
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
            val state = store.load(ConversationId("C123", "1700000000.000"))
            val savedMessage = state.messages.single { it.id == message.id }
            assertEquals("AGENT_FAILURE", savedMessage.skippedReason)
            assertFalse(savedMessage.replied == true)
            assertEquals(emptyList(), state.messages.filter { it.role == SessionMessageRole.ASSISTANT })
        }

    @Test
    fun `delivers reply attachments and removes staged files`() =
        runBlocking {
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            config.botUsername = "USIDEKICK"
            val store = FilesystemConversationStateStore(config)
            val manager = conversationManager(config, store)
            val stagedFile = dir.resolve("staged/report.csv")
            Files.createDirectories(stagedFile.parent)
            Files.writeString(stagedFile, "report\n")
            val attachment = ReplyAttachment(stagedFile, "report.csv", "text/csv", Files.size(stagedFile))
            val delivered = mutableListOf<ChatReply>()
            val executor =
                TurnExecutor(
                    turnTrigger = InboundMessageFilter(manager),
                    conversationManager = manager,
                    replyTrigger = replyDecisionService(),
                    agentConfig = config,
                    agent = AgentTurnRunner { _, _, _ -> ChatReply("Attached the report.", listOf(attachment)) },
                    skills = { SkillCatalog(emptyList()) },
                )
            val chat =
                object : ChatPlatformAdapter {
                    override val botUsername = "USIDEKICK"
                    override val activity = NoopActivityIndicator

                    override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

                    override suspend fun postReply(reply: ChatReply): ReplyResult {
                        delivered += reply
                        return ReplyResult("reply", 1)
                    }

                    override fun ingestFiles(
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

            executor.run(ChatConversationId("C123"), listOf(message), chat)

            assertEquals(listOf(attachment), delivered.single().attachments)
            assertFalse(Files.exists(stagedFile))
            assertEquals(
                emptyList(),
                store.load(ConversationId("C123", "1700000000.000")).messages.single { it.role == SessionMessageRole.ASSISTANT }.fileIds,
            )
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
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.5",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            maxAgentIterations = 50,
        )

    private fun chat(replies: MutableList<String>): ChatPlatformAdapter =
        object : ChatPlatformAdapter {
            override val botUsername = "USIDEKICK"
            override val activity = NoopActivityIndicator

            override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

            override suspend fun postReply(reply: ChatReply): ReplyResult {
                replies += reply.text
                return ReplyResult("reply-${replies.size}", replies.size.toLong())
            }

            override fun ingestFiles(
                conversationId: ConversationId,
                files: List<IncomingChatFile>,
            ): List<IncomingChatFile> = files

        }
}

private object NoopActivityIndicator : TurnActivityIndicator {
    override fun start(text: String?) = Unit

    override fun `continue`(text: String?) = Unit

    override fun toolCall(name: String) = Unit

    override fun clear() = Unit

    override fun endTurn() = Unit
}
