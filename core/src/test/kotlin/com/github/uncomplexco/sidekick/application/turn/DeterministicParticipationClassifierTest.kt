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
            val inputs = mutableListOf(input("thanks"), input("what do you mean?"), input(""))
            inputs[position] = input("help with this", explicitMention = true)

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `mention in text replies without the explicit mention flag`() {
            // Arrange
            val inputs = listOf(input("thanks"), input("<@sidekick> help"), input(""))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `one private request replies despite other skipped or ambiguous messages`() {
            // Arrange
            val inputs =
                listOf(
                    input("<@U123> check the logs"),
                    input("what do you mean?"),
                    input("check the deployment", privateMessage = true),
                    input("thanks"),
                )

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
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
            val inputs = listOf(input(command))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
            assertEquals(TurnParticipationReason.UNSUBSCRIBE_COMMAND, decision.reason)
        }

        @Test
        fun `unsubscribe after an explicit mention wins even with a later passive request`() {
            // Arrange
            val inputs =
                listOf(
                    input("help", explicitMention = true),
                    input("unsubscribe"),
                    input("what do you mean?"),
                )

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `explicit mention after unsubscribe wins even with a later acknowledgment`() {
            // Arrange
            val inputs = listOf(input("unsubscribe"), input("<@sidekick> help"), input("thanks"))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `last unsubscribe wins across repeated subscription changes`() {
            // Arrange
            val inputs =
                listOf(
                    input("unsubscribe"),
                    input("help", explicitMention = true),
                    input("stop replying"),
                    input("thanks"),
                )

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `last explicit mention wins across repeated subscription changes`() {
            // Arrange
            val inputs =
                listOf(
                    input("help", explicitMention = true),
                    input("unsubscribe"),
                    input("help again", explicitMention = true),
                    input(""),
                )

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Respond>(decision)
            assertEquals(TurnParticipationReason.EXPLICIT_MENTION, decision.reason)
        }

        @Test
        fun `mention requesting unsubscribe selects unsubscribe`() {
            // Arrange
            val inputs = listOf(input("<@sidekick> unsubscribe", explicitMention = true))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }

        @Test
        fun `private request does not override unsubscribe`() {
            // Arrange
            val inputs = listOf(input("unsubscribe"), input("help", privateMessage = true))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Unsubscribe>(decision)
        }
    }

    @Nested
    inner class Skipping {
        @Test
        fun `all empty messages skip`() {
            // Arrange
            val inputs = listOf(input(""), input(" \n "))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.EMPTY_MESSAGE, decision.reason)
        }

        @Test
        fun `all acknowledgments skip`() {
            // Arrange
            val inputs = listOf(input("thanks"), input("got it"), input("okay!"))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.ACKNOWLEDGMENT, decision.reason)
        }

        @Test
        fun `all messages directed at other people skip`() {
            // Arrange
            val inputs = listOf(input("<@U123> check logs"), input("<@U456> check metrics"))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
        }

        @Test
        fun `different deterministic skips still skip the whole batch`() {
            // Arrange
            val inputs = listOf(input("thanks"), input(""), input("<@U123> check logs"))

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
        }

        @Test
        fun `passive requests without assistant history skip`() {
            // Arrange
            val inputs =
                listOf(
                    input("check the deployment", hasAssistantHistory = false),
                    input("check the logs", hasAssistantHistory = false),
                )

            // Act
            val decision = classifier.classify(inputs)

            // Assert
            assertIs<TurnParticipationDecision.Skip>(decision)
            assertEquals(TurnParticipationReason.SIDE_CONVERSATION, decision.reason)
        }
    }

    private fun input(
        text: String,
        explicitMention: Boolean = false,
        privateMessage: Boolean = false,
        hasAssistantHistory: Boolean = true,
    ) =
        ParticipationClassifierInput(
            text = text,
            botUser = MessageAuthor(username = "sidekick", fullName = "Sidekick"),
            messageHistory = emptyList(),
            hasAssistantHistory = hasAssistantHistory,
            isExplicitMention = explicitMention,
            isPrivateMessage = privateMessage,
        )
}
