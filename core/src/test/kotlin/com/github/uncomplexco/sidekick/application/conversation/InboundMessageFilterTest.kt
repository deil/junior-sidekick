package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.turn.InboundMessageFilter
import com.github.uncomplexco.sidekick.application.turn.TurnTriggerDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class InboundMessageFilterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `app mention in channel root creates session from message id`() {
        // Arrange
        val policy = policy()
        val message = message(ChatMessageType.EXPLICIT_MENTION, id = "1700000000.001")
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

        // Assert
        val handle = assertIs<TurnTriggerDecision.ShouldHandle>(decision)
        assertEquals(ConversationId("C123", "1700000000.001"), handle.conversationId)
        assertEquals(false, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `app mention in thread continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(ChatMessageType.EXPLICIT_MENTION)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

        // Assert
        val handle = assertIs<TurnTriggerDecision.ShouldHandle>(decision)
        assertEquals(ConversationId("C123", "1700000000.000"), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `passive channel root message is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(ChatMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

        // Assert
        assertSame(TurnTriggerDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message without existing session is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(ChatMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

        // Assert
        assertSame(TurnTriggerDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message with existing session continues without seeding`() =
        runBlocking {
            // Arrange
            val agentSessions = agentSessions()
            val sessionId = ConversationId("C123", "1700000000.000")
            agentSessions.recordIncomingMessage(
                conversationId = sessionId,
                seedHistory = false,
                historyLoader = { emptyList() },
                message =
                    SessionMessage(
                        id = "seed",
                        role = SessionMessageRole.USER,
                        author = author(),
                        text = "existing",
                        fileIds = emptyList(),
                        createdAtMs = 1,
                    ),
                files = emptyList(),
            )
            val policy = InboundMessageFilter(agentSessions)
            val message = message(ChatMessageType.PASSIVE_MESSAGE)
            val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

            // Act
            val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

            // Assert
            val handle = assertIs<TurnTriggerDecision.ShouldHandle>(decision)
            assertEquals(sessionId, handle.conversationId)
            assertEquals(false, handle.seedHistory)
            assertEquals(false, handle.explicitMention)
        }

    @Test
    fun `assistant message continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(ChatMessageType.ASSISTANT_MESSAGE)
        val conversationId = ChatConversationId(channelId = "D123", threadId = "1700000000.000")

        // Act
        val decision = policy.shouldTriggerTurn(conversationId, message.type, message.id)

        // Assert
        val handle = assertIs<TurnTriggerDecision.ShouldHandle>(decision)
        assertEquals(ConversationId("D123", "1700000000.000"), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(false, handle.explicitMention)
    }

    private fun policy(): InboundMessageFilter = InboundMessageFilter(agentSessions())

    private fun agentSessions(): SessionManager {
        val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
        return SessionManager(
            FilesystemConversationStateStore(config),
            SessionContextCompactor(
                TurnPromptBuilder(config),
                summarizer = { _, messages, _ -> "summary for ${messages.size} messages" },
            ),
        )
    }

    private fun message(
        trigger: ChatMessageType,
        id: String = "1700000000.002",
    ): InboundMessage =
        InboundMessage(
            id = id,
            createdAtMs = 2,
            sender = author(),
            text = "hello",
            type = trigger,
        )

    private fun author(): MessageAuthor = MessageAuthor(username = "U123", fullName = "User")
}
