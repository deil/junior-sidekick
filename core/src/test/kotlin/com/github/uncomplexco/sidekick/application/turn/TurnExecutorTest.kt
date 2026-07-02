package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
            assertEquals(
                listOf("I hit a temporary model/provider error while processing this. Please retry in a minute."),
                replies,
            )
            val state = store.load(ConversationId("C123", "1700000000.000"))
            val savedMessage = state.messages.single { it.id == message.id }
            assertEquals("AGENT_FAILURE", savedMessage.skippedReason)
            assertFalse(savedMessage.replied == true)
            assertEquals(emptyList(), state.messages.filter { it.role == SessionMessageRole.ASSISTANT })
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
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.5",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            maxAgentIterations = 50,
        )

    private fun chat(replies: MutableList<String>): ChatPlatformAdapter =
        ChatPlatformAdapter(
            botUsername = "USIDEKICK",
            historyLoader = { emptyList() },
            reply = { text ->
                replies += text
                ReplyResult("reply-${replies.size}", replies.size.toLong())
            },
            activity = NoopActivityIndicator,
            fileIngestor = { _, files -> files },
        )
}

private object NoopActivityIndicator : ChatActivityIndicator {
    override fun start(text: String?) = Unit

    override fun `continue`(text: String?) = Unit

    override fun toolCall(name: String) = Unit

    override fun clear() = Unit

    override fun endTurn() = Unit
}
