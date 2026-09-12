package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatConversationKind
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.turn.TurnAdmission
import com.github.uncomplexco.sidekick.application.turn.TurnAdmissionDecision
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TurnAdmissionTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `app mention in channel root creates session from message id`() {
        // Arrange
        val policy = policy()
        val message = message(IncomingMessageType.EXPLICIT_MENTION, id = "1700000000.001")
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("C123", "1700000000.001"), handle.conversationId)
        assertEquals(false, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `app mention in thread continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(IncomingMessageType.EXPLICIT_MENTION)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("C123", "1700000000.000"), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `passive channel root message is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(IncomingMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        assertSame(TurnAdmissionDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message without existing session is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(IncomingMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        assertSame(TurnAdmissionDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message with existing session continues without seeding`() = runBlocking {
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
        val policy = TurnAdmission(agentSessions)
        val message = message(IncomingMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(sessionId, handle.conversationId)
        assertEquals(false, handle.seedHistory)
        assertEquals(false, handle.explicitMention)
    }

    @Test
    fun `passive thread message with unsubscribed session is ignored`() = runBlocking {
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
        agentSessions.setSubscribed(sessionId, false)
        val policy = TurnAdmission(agentSessions)
        val message = message(IncomingMessageType.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        assertSame(TurnAdmissionDecision.Ignore, decision)
    }

    @Test
    fun `explicit mention in unsubscribed thread is admitted without mutating subscription`() =
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
            agentSessions.setSubscribed(sessionId, false)
            val policy = TurnAdmission(agentSessions)
            val message = message(IncomingMessageType.EXPLICIT_MENTION)
            val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

            // Act
            val decision = trigger(policy, conversationId, message)

            // Assert
            val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
            assertEquals(sessionId, handle.conversationId)
            assertEquals(true, handle.explicitMention)
            assertEquals(false, agentSessions.isSubscribed(sessionId))
        }

    @Test
    fun `assistant message continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(IncomingMessageType.ASSISTANT_MESSAGE)
        val conversationId = ChatConversationId(channelId = "D123", threadId = "1700000000.000")

        // Act
        val decision = trigger(policy, conversationId, message)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("D123", "1700000000.000"), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(false, handle.explicitMention)
    }

    @Test
    fun `unthreaded slack assistant message keeps history seeding semantics`() {
        val policy = policy()
        val message = message(IncomingMessageType.ASSISTANT_MESSAGE)
        val conversationId = ChatConversationId(channelId = "D123")

        val decision = trigger(policy, conversationId, message)

        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("D123", ""), handle.conversationId)
        assertEquals(true, handle.seedHistory)
    }

    @Test
    fun `discord direct message uses real channel as session and requests history seeding`() {
        val policy = policy()
        val message = message(IncomingMessageType.ASSISTANT_MESSAGE)
        val conversationId =
            ChatConversationId(
                channelId = "123456789012345678",
                platform = ChatPlatform.DISCORD,
                kind = ChatConversationKind.DIRECT_MESSAGE,
            )

        val decision = trigger(policy, conversationId, message)

        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("123456789012345678", ""), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(false, handle.explicitMention)
    }

    @Test
    fun `empty batch is ignored`() {
        // Arrange
        val policy = policy()
        val conversationId = ChatConversationId(channelId = "C123")
        val messages = emptyList<IncomingMessage>()

        // Act
        val decision = trigger(policy, conversationId, messages)

        // Assert
        assertSame(TurnAdmissionDecision.Ignore, decision)
    }

    @Test
    fun `batch with only ignored messages is ignored`() {
        // Arrange
        val policy = policy()
        val conversationId = ChatConversationId(channelId = "C123")
        val messages =
            listOf(
                message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.001"),
                message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.002"),
            )

        // Act
        val decision = trigger(policy, conversationId, messages)

        // Assert
        assertSame(TurnAdmissionDecision.Ignore, decision)
    }

    @Test
    fun `passive thread batch with existing conversation continues without seeding`() =
        runBlocking {
            // Arrange
            val agentSessions = agentSessions()
            val conversationId = ConversationId("C123", "1700000000.000")
            agentSessions.recordIncomingMessage(
                conversationId = conversationId,
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
            val policy = TurnAdmission(agentSessions)
            val chatConversationId =
                ChatConversationId(channelId = "C123", threadId = "1700000000.000")
            val messages =
                listOf(
                    message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.001"),
                    message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.002"),
                )

            // Act
            val decision = trigger(policy, chatConversationId, messages)

            // Assert
            val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
            assertEquals(conversationId, handle.conversationId)
            assertEquals(false, handle.seedHistory)
            assertEquals(false, handle.explicitMention)
        }

    @Test
    fun `thread batch aggregates explicit mention and seed history with any true`() {
        // Arrange
        val policy = policy()
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")
        val messages =
            listOf(
                message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.001"),
                message(IncomingMessageType.EXPLICIT_MENTION, id = "1700000000.002"),
            )

        // Act
        val decision = trigger(policy, conversationId, messages)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("C123", "1700000000.000"), handle.conversationId)
        assertEquals(true, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `batch ignores ignored messages when at least one message should handle`() {
        // Arrange
        val policy = policy()
        val conversationId = ChatConversationId(channelId = "C123")
        val messages =
            listOf(
                message(IncomingMessageType.PASSIVE_MESSAGE, id = "1700000000.001"),
                message(IncomingMessageType.EXPLICIT_MENTION, id = "1700000000.002"),
            )

        // Act
        val decision = trigger(policy, conversationId, messages)

        // Assert
        val handle = assertIs<TurnAdmissionDecision.Admitted>(decision)
        assertEquals(ConversationId("C123", "1700000000.002"), handle.conversationId)
        assertEquals(false, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `batch with multiple handled conversation ids is rejected`() {
        // Arrange
        val policy = policy()
        val conversationId = ChatConversationId(channelId = "C123")
        val messages =
            listOf(
                message(IncomingMessageType.EXPLICIT_MENTION, id = "1700000000.001"),
                message(IncomingMessageType.EXPLICIT_MENTION, id = "1700000000.002"),
            )

        // Act / Assert
        assertFailsWith<IllegalArgumentException> { trigger(policy, conversationId, messages) }
    }

    private fun policy(): TurnAdmission = TurnAdmission(agentSessions())

    private fun trigger(
        policy: TurnAdmission,
        conversationId: ChatConversationId,
        message: IncomingMessage,
    ): TurnAdmissionDecision = policy.admit(IncomingBatch(conversationId, listOf(message)))

    private fun trigger(
        policy: TurnAdmission,
        conversationId: ChatConversationId,
        messages: List<IncomingMessage>,
    ): TurnAdmissionDecision = policy.admit(IncomingBatch(conversationId, messages))

    private fun agentSessions(): SessionManager {
        val config =
            AgentConfig(
                "Sidekick",
                dir.resolve("state").toString(),
                dir.resolve("workspace").toString(),
            )
        return SessionManager(
            FilesystemConversationStateStore(config, ChatPlatform.SLACK),
            VirtualPathsFactory(config, ChatPlatform.SLACK),
            SessionContextCompactor(
                summarizer = { _, _, messages -> "summary for ${messages.size} messages" }
            ),
        )
    }

    private fun message(
        trigger: IncomingMessageType,
        id: String = "1700000000.002",
    ): IncomingMessage =
        IncomingMessage(
            id = id,
            createdAtMs = 2,
            sender = author(),
            text = "hello",
            type = trigger,
        )

    private fun author(): MessageAuthor = MessageAuthor(username = "U123", fullName = "User")
}
