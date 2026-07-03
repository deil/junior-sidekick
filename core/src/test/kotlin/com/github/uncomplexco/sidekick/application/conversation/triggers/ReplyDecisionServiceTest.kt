package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.turn.LlmReplyDecisionClassifier
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionInput
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionReason
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionService
import com.github.uncomplexco.sidekick.application.turn.SimpleReplyDecisionClassifier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyDecisionServiceTest {
    @Test
    fun `explicit mention always replies`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "@heytech help",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isExplicitMention = true,
            )

        // Act
        val decision =
            classifier.classify(input)!!

        // Assert
        assertEquals(ReplyDecisionReason.EXPLICIT_MENTION, decision.reason)
        assertEquals(true, decision.shouldReply)
    }

    @Test
    fun `explicit unsubscribe command short-circuits reply and unsubscribes`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val commands =
            listOf(
                "<@sidekick> unsubscribe",
                "<@sidekick> stop replying",
                "<@sidekick> stop responding",
                "<@sidekick> stop participating",
                "<@sidekick> stop watching",
                "<@sidekick> mute this thread",
                "<@sidekick> leave this thread",
                "<@sidekick> don't reply",
                "<@sidekick> don't participate",
                "<@sidekick> don't watch",
            )

        commands.forEach { command ->
            val input =
                ReplyDecisionInput(
                    text = command,
                    botUser = botUser(),
                    messageHistory = emptyList(),
                    hasAssistantHistory = true,
                    isExplicitMention = true,
                )

            // Act
            val decision = classifier.classify(input)!!

            // Assert
            assertEquals(false, decision.shouldReply, command)
            assertEquals(true, decision.shouldUnsubscribe, command)
            assertEquals(ReplyDecisionReason.UNSUBSCRIBE_COMMAND, decision.reason, command)
        }
    }

    @Test
    fun `explicit unsubscribe command is detected independent of scope`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "<@sidekick> unsubscribe",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
                isExplicitMention = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertEquals(false, decision.shouldReply)
        assertEquals(true, decision.shouldUnsubscribe)
        assertEquals(ReplyDecisionReason.UNSUBSCRIBE_COMMAND, decision.reason)
    }

    @Test
    fun `slack user mention to someone else does not reply`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "<@U039RPWU0V8> test",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision =
            classifier.classify(input)!!

        // Assert
        assertEquals(ReplyDecisionReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
        assertEquals(false, decision.shouldReply)
    }

    @Test
    fun `acknowledgment does not reply`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "thanks",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision =
            classifier.classify(input)!!

        // Assert
        assertEquals(ReplyDecisionReason.ACKNOWLEDGMENT, decision.reason)
        assertEquals(false, decision.shouldReply)
    }

    @Test
    fun `private message without assistant history delegates to llm classifier`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "can you help with this?",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isPrivateMessage = true,
            )

        // Act
        val decision = classifier.classify(input)

        // Assert
        assertNull(decision)
    }

    @Test
    fun `subscribed ambiguous message delegates to llm classifier`() {
        // Arrange
        val classifier = SimpleReplyDecisionClassifier()
        val input =
            ReplyDecisionInput(
                text = "what do you mean by that?",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision = classifier.classify(input)

        // Assert
        assertNull(decision)
    }

    @Test
    fun `private message without prior assistant message can be classified by llm`() =
        runBlocking {
            // Arrange
            val service =
                ReplyDecisionService(
                    SimpleReplyDecisionClassifier(),
                    LlmReplyDecisionClassifier(koogConfig()) { _, _ ->
                        LlmReplyDecisionClassifier.ReplyClassifierResult(
                            shouldReply = true,
                            confidence = 0.95,
                            reason = "direct_private_request",
                        )
                    },
                )
            val input =
                ReplyDecisionInput(
                    text = "identify where https://headshots.ltd is hosted; use bash tool",
                    botUser = botUser(),
                    messageHistory =
                        listOf(
                            SessionMessage(
                                id = "m1",
                                role = SessionMessageRole.USER,
                                author = MessageAuthor(username = "anton", fullName = "Anton"),
                                text = "identify where https://headshots.ltd is hosted; use bash tool",
                                createdAtMs = 1,
                            ),
                        ),
                    hasAssistantHistory = false,
                    isPrivateMessage = true,
                )

            // Act
            val decision = service.shouldReply(input)

            // Assert
            assertEquals(true, decision.shouldReply)
            assertEquals(ReplyDecisionReason.CLASSIFIER, decision.reason)
            assertEquals("direct_private_request", decision.detail)
        }

    @Test
    fun `llm classifier failure returns classifier error instead of crashing`() =
        runBlocking {
            // Arrange
            val classifier =
                LlmReplyDecisionClassifier(koogConfig()) { _, _ ->
                    error("llm unavailable")
                }
            val input =
                ReplyDecisionInput(
                    text = "can you check this?",
                    botUser = botUser(),
                    messageHistory = emptyList(),
                    hasAssistantHistory = false,
                    isPrivateMessage = true,
                )

            // Act
            val decision = classifier.classify(input)

            // Assert
            assertEquals(false, decision.shouldReply)
            assertEquals(ReplyDecisionReason.CLASSIFIER_ERROR, decision.reason)
            assertEquals("llm unavailable", decision.detail)
        }

    private fun botUser() = MessageAuthor(username = "sidekick", fullName = "Sidekick")

    private fun koogConfig() =
        KoogConfig(
            openRouterApiKey = "test-key",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "openai/gpt-5.4-mini",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.4-mini",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            maxAgentIterations = 50,
        )
}
