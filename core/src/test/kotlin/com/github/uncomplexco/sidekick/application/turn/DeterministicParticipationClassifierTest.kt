package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DeterministicParticipationClassifierTest {
    private val classifier = DeterministicParticipationClassifier()

    @Nested
    inner class Replying {
        @ParameterizedTest
        @ValueSource(ints = [0, 1, 2])
        fun `explicit mention replies regardless of its position in the batch`(position: Int) {
            // Arrange
            val messages =
                mutableListOf(message("thanks"), message("what do you mean?"), message(""))
            messages[position] = message("help with this", explicitMention = true)

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `mention in text replies without the explicit mention flag`() {
            // Arrange
            val messages = listOf(message("thanks"), message("<@sidekick> help"), message(""))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `private batch replies to a request despite skipped messages and regardless of history`(
            hasHistory: Boolean
        ) {
            // Arrange
            val messages =
                listOf(
                    message("<@U123> check the logs"),
                    message("check the deployment"),
                    message("thanks"),
                )

            // Act
            val decision =
                classifier.classify(
                    input(messages, privateMessage = true, hasAssistantHistory = hasHistory)
                )

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
        }

        @Test
        fun `singleton private request without assistant history replies`() {
            // Arrange
            val messages =
                listOf(message("identify where https://headshots.ltd is hosted; use bash tool"))

            // Act
            val decision =
                classifier.classify(
                    input(messages, privateMessage = true, hasAssistantHistory = false)
                )

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
            assertEquals(null, decision.detail)
        }

        @Test
        fun `singleton explicit mention replies without assistant history`() {
            // Arrange
            val messages = listOf(message("@heytech help", explicitMention = true))

            // Act
            val decision = classifier.classify(input(messages, hasAssistantHistory = false))

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }
    }

    @Nested
    inner class Subscription {
        @ParameterizedTest
        @ValueSource(
            strings =
                [
                    "unsubscribe",
                    "stop replying",
                    "stop responding",
                    "stop participating",
                    "stop watching",
                    "mute this thread",
                    "leave this thread",
                    "don't reply",
                    "don't participate",
                    "don't watch",
                ]
        )
        fun `unsubscribe command needs no explicit mention`(command: String) {
            // Arrange
            val messages = listOf(message(command))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
            assertEquals(TurnParticipationReason.UNSUBSCRIBE_COMMAND, decision.reason)
        }

        @Test
        fun `unsubscribe after an explicit mention wins even with a later passive request`() {
            // Arrange
            val messages =
                listOf(
                    message("help", explicitMention = true),
                    message("unsubscribe"),
                    message("what do you mean?"),
                )

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `explicit mention after unsubscribe wins even with a later acknowledgment`() {
            // Arrange
            val messages =
                listOf(message("unsubscribe"), message("<@sidekick> help"), message("thanks"))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `last unsubscribe wins across repeated subscription changes`() {
            // Arrange
            val messages =
                listOf(
                    message("unsubscribe"),
                    message("help", explicitMention = true),
                    message("stop replying"),
                    message("thanks"),
                )

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `last explicit mention wins across repeated subscription changes`() {
            // Arrange
            val messages =
                listOf(
                    message("help", explicitMention = true),
                    message("unsubscribe"),
                    message("help again", explicitMention = true),
                    message(""),
                )

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `mention requesting unsubscribe selects unsubscribe with or without the flag`(
            explicitMention: Boolean
        ) {
            // Arrange
            val messages =
                listOf(message("<@sidekick> unsubscribe", explicitMention = explicitMention))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `private request does not override unsubscribe`() {
            // Arrange
            val messages = listOf(message("unsubscribe"), message("help"))

            // Act
            val decision = classifier.classify(input(messages, privateMessage = true))

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }
    }

    @Nested
    inner class Skipping {
        @Test
        fun `all empty messages skip`() {
            // Arrange
            val messages = listOf(message(""), message(" \n "))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.EMPTY_MESSAGE, decision.reason)
        }

        @Test
        fun `all acknowledgments skip`() {
            // Arrange
            val messages = listOf(message("thanks"), message("got it"), message("okay!"))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.ACKNOWLEDGMENT, decision.reason)
        }

        @Test
        fun `all messages directed at other people skip`() {
            // Arrange
            val messages = listOf(message("<@U123> check logs"), message("<@U456> check metrics"))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
        }

        @Test
        fun `different deterministic skips still skip the whole batch`() {
            // Arrange
            val messages = listOf(message("thanks"), message(""), message("<@U123> check logs"))

            // Act
            val decision = classifier.classify(input(messages))

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
        }

        @Test
        fun `passive requests without assistant history skip`() {
            // Arrange
            val messages = listOf(message("check the deployment"), message("check the logs"))

            // Act
            val decision = classifier.classify(input(messages, hasAssistantHistory = false))

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.SIDE_CONVERSATION, decision.reason)
        }
    }

    private fun message(text: String, explicitMention: Boolean = false) =
        ParticipationClassifierMessage(text, MessageAuthor("U123", "User"), explicitMention)

    private fun input(
        messages: List<ParticipationClassifierMessage>,
        privateMessage: Boolean = false,
        hasAssistantHistory: Boolean = true,
    ) =
        ParticipationClassifierInput(
            messages = messages,
            botUser = MessageAuthor("sidekick", "Sidekick"),
            messageHistory = emptyList(),
            hasAssistantHistory = hasAssistantHistory,
            isPrivateMessage = privateMessage,
        )
}
