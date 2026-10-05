package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.DeterministicParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.LlmParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.ParticipationClassifierInput
import com.github.uncomplexco.sidekick.application.turn.RecordedTurn
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.TurnParticipation
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationDecision
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationInput
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationReason
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TurnParticipationTest {
    @TempDir lateinit var dir: Path

    @Nested
    inner class LlmDelegation {
        @Test
        fun `ambiguous message calls llm classifier and uses its decision`() = runBlocking {
            // Arrange
            val config =
                AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
                    it.botUsername = "sidekick"
                }
            val llmClassifier = mock(LlmParticipationClassifier::class.java)
            val participation =
                TurnParticipation(
                    config,
                    DeterministicParticipationClassifier(),
                    llmClassifier,
                )
            val message =
                IncomingMessage(
                    id = "m1",
                    createdAtMs = 2,
                    sender = MessageAuthor("U123", "User"),
                    text = "what do you mean by that?",
                    type = IncomingMessageType.PASSIVE_MESSAGE,
                )
            val conversationId = ConversationId("C123", "thread1")
            val turn = recordedTurn(config, conversationId, message)

            val classifierInput =
                ParticipationClassifierInput(
                    text = message.text,
                    botUser = botUser(),
                    messageHistory = turn.context.conversation.history.messages,
                    hasAssistantHistory = true,
                    conversationId = conversationId,
                )
            val expectedDecision =
                TurnParticipationDecision.Respond(
                    TurnParticipationReason.CLASSIFIER,
                    "follow-up question",
                )
            `when`(llmClassifier.classify(classifierInput)).thenReturn(expectedDecision)

            // Act
            val decision = participation.decide(TurnParticipationInput(message, turn, false))

            // Assert
            verify(llmClassifier).classify(classifierInput)
            assertEquals(expectedDecision, decision)
        }
    }

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
        val decision = classifier.classify(listOf(input))!!

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
            val decision = classifier.classify(listOf(input))!!

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
        val decision = classifier.classify(listOf(input))!!

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
        val decision = classifier.classify(listOf(input))!!

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
        val decision = classifier.classify(listOf(input))!!

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
        val decision = classifier.classify(listOf(input))!!

        // Assert
        assertIs<TurnParticipationDecision.Respond>(decision)
        assertEquals(TurnParticipationReason.PRIVATE_MESSAGE, decision.reason)
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
        val decision = classifier.classify(listOf(input))!!

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

    private fun recordedTurn(
        config: AgentConfig,
        conversationId: ConversationId,
        message: IncomingMessage,
    ) =
        RecordedTurn(
            context =
                TurnContext(
                    conversation =
                        ConversationContext(
                            conversationId = conversationId,
                            virtualPaths =
                                VirtualPathsFactory(config, ChatPlatform.SLACK)
                                    .forConversation(conversationId),
                            history =
                                ConversationHistory(
                                    compactions = emptyList(),
                                    messages =
                                        listOf(
                                            SessionMessage(
                                                id = "a1",
                                                role = SessionMessageRole.ASSISTANT,
                                                text = "The deployment is ready.",
                                                createdAtMs = 1,
                                            )
                                        ),
                                    hasKoogMessages = true,
                                ),
                            mcpServers = emptyList(),
                        ),
                    turnId = "turn1",
                    lastMessageId = message.id,
                    sessionFiles = emptyList(),
                    aiModelProfile = AiModelProfile.NORMAL,
                ),
            messages =
                listOf(
                    SessionMessage(
                        id = message.id,
                        role = SessionMessageRole.USER,
                        author = message.sender,
                        text = message.text,
                        createdAtMs = message.createdAtMs,
                    )
                ),
        )

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
