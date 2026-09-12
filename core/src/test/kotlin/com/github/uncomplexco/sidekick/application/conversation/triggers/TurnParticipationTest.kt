package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.turn.DeterministicParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.LlmParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.ParticipationClassifierInput
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationDecision
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationReason
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TurnParticipationTest {
    @Test
    fun `explicit mention always replies`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "@heytech help",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isExplicitMention = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Respond>(decision)
        assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
    }

    @Test
    fun `explicit unsubscribe command short-circuits reply and unsubscribes`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
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
                ParticipationClassifierInput(
                    text = command,
                    botUser = botUser(),
                    messageHistory = emptyList(),
                    hasAssistantHistory = true,
                    isExplicitMention = true,
                )

            // Act
            val decision = classifier.classify(input)!!

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision, command)
            assertEquals(TurnParticipationReason.UNSUBSCRIBE_COMMAND, decision.reason, command)
        }
    }

    @Test
    fun `explicit unsubscribe command is detected independent of scope`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "<@sidekick> unsubscribe",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
                isExplicitMention = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        assertEquals(TurnParticipationReason.UNSUBSCRIBE_COMMAND, decision.reason)
    }

    @Test
    fun `slack user mention to someone else does not reply`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "<@U039RPWU0V8> test",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Skip>(decision)
        assertEquals(TurnParticipationReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
    }

    @Test
    fun `acknowledgment does not reply`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "thanks",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Skip>(decision)
        assertEquals(TurnParticipationReason.ACKNOWLEDGMENT, decision.reason)
    }

    @Test
    fun `private message without assistant history replies without llm classifier`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "can you help with this?",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isPrivateMessage = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Respond>(decision)
        assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
    }

    @Test
    fun `subscribed ambiguous message delegates to llm classifier`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "what do you mean by that?",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = true,
            )

        // Act
        val decision = classifier.classify(input)

        // Assert
        assertEquals(null, decision)
    }

    @Test
    fun `private message without prior assistant message is handled by simple classifier`() {
        // Arrange
        val classifier = DeterministicParticipationClassifier()
        val input =
            ParticipationClassifierInput(
                text = "identify where https://headshots.ltd is hosted; use bash tool",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isPrivateMessage = true,
            )

        // Act
        val decision = classifier.classify(input)!!

        // Assert
        assertIs<TurnParticipationDecision.Respond>(decision)
        assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
        assertEquals(null, decision.detail)
    }

    @Test
    fun `llm classifier failure returns classifier error instead of crashing`() = runBlocking {
        // Arrange
        val classifier =
            LlmParticipationClassifier(koogConfig()) { _, _ -> error("llm unavailable") }
        val input =
            ParticipationClassifierInput(
                text = "can you check this?",
                botUser = botUser(),
                messageHistory = emptyList(),
                hasAssistantHistory = false,
                isPrivateMessage = true,
            )

        // Act
        val decision = classifier.classify(input)

        // Assert
        assertIs<TurnParticipationDecision.Skip>(decision)
        assertEquals(TurnParticipationReason.CLASSIFIER_ERROR, decision.reason)
        assertEquals("llm unavailable", decision.detail)
    }

    private fun botUser() = MessageAuthor(username = "sidekick", fullName = "Sidekick")

    private fun koogConfig() =
        KoogConfig(
            openRouterApiKey = "test-key",
            openRouterAppTitle = "Sidekick",
            openRouterAppUrl = "",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "openai/gpt-5.4-mini",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.4-mini",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            imageModel = "image-model",
            maxAgentIterations = 50,
        )
}
