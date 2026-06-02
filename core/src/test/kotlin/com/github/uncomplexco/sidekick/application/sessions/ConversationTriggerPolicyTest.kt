package com.github.uncomplexco.sidekick.application.sessions

import com.github.uncomplexco.sidekick.application.IncomingChatMessage
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.context.PromptBuilder
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.sessions.triggers.ChatTrigger
import com.github.uncomplexco.sidekick.application.sessions.triggers.ConversationTriggerPolicy
import com.github.uncomplexco.sidekick.application.sessions.triggers.TriggerDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ConversationTriggerPolicyTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `app mention in channel root creates session from message id`() {
        // Arrange
        val policy = policy()
        val message = message(ChatTrigger.APP_MENTION, id = "1700000000.001")
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = policy.decide(message.id, message.trigger, conversationId)

        // Assert
        val handle = assertIs<TriggerDecision.Handle>(decision)
        assertEquals(SessionId("C123", "1700000000.001"), handle.sessionId)
        assertEquals(false, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `app mention in thread continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(ChatTrigger.APP_MENTION)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = policy.decide(message.id, message.trigger, conversationId)

        // Assert
        val handle = assertIs<TriggerDecision.Handle>(decision)
        assertEquals(SessionId("C123", "1700000000.000"), handle.sessionId)
        assertEquals(true, handle.seedHistory)
        assertEquals(true, handle.explicitMention)
    }

    @Test
    fun `passive channel root message is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(ChatTrigger.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123")

        // Act
        val decision = policy.decide(message.id, message.trigger, conversationId)

        // Assert
        assertSame(TriggerDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message without existing session is ignored`() {
        // Arrange
        val policy = policy()
        val message = message(ChatTrigger.PASSIVE_MESSAGE)
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

        // Act
        val decision = policy.decide(message.id, message.trigger, conversationId)

        // Assert
        assertSame(TriggerDecision.Ignore, decision)
    }

    @Test
    fun `passive thread message with existing session continues without seeding`() =
        runBlocking {
            // Arrange
            val agentSessions = agentSessions()
            val sessionId = SessionId("C123", "1700000000.000")
            agentSessions.recordIncomingMessage(
                sessionId = sessionId,
                seedHistory = false,
                historyLoader = { emptyList() },
                message =
                    SessionMessage(
                        id = "seed",
                        role = MessageRole.USER,
                        author = author(),
                        text = "existing",
                        createdAtMs = 1,
                    ),
            )
            val policy = ConversationTriggerPolicy(agentSessions)
            val message = message(ChatTrigger.PASSIVE_MESSAGE)
            val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")

            // Act
            val decision = policy.decide(message.id, message.trigger, conversationId)

            // Assert
            val handle = assertIs<TriggerDecision.Handle>(decision)
            assertEquals(sessionId, handle.sessionId)
            assertEquals(false, handle.seedHistory)
            assertEquals(false, handle.explicitMention)
        }

    @Test
    fun `assistant message continues thread session and seeds history`() {
        // Arrange
        val policy = policy()
        val message = message(ChatTrigger.ASSISTANT_MESSAGE)
        val conversationId = ChatConversationId(channelId = "D123", threadId = "1700000000.000")

        // Act
        val decision = policy.decide(message.id, message.trigger, conversationId)

        // Assert
        val handle = assertIs<TriggerDecision.Handle>(decision)
        assertEquals(SessionId("D123", "1700000000.000"), handle.sessionId)
        assertEquals(true, handle.seedHistory)
        assertEquals(false, handle.explicitMention)
    }

    private fun policy(): ConversationTriggerPolicy = ConversationTriggerPolicy(agentSessions())

    private fun agentSessions(): AgentSessions {
        val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
        return AgentSessions(
            config,
            SessionContextCompactor(
                PromptBuilder(config),
                summarizer = { messages -> "summary for ${messages.size} messages" },
            ),
        )
    }

    private fun message(
        trigger: ChatTrigger,
        id: String = "1700000000.002",
    ): IncomingChatMessage =
        IncomingChatMessage(
            id = id,
            createdAtMs = 2,
            sender = author(),
            text = "hello",
            trigger = trigger,
        )

    private fun author(): MessageAuthor = MessageAuthor(username = "U123", fullName = "User")
}
