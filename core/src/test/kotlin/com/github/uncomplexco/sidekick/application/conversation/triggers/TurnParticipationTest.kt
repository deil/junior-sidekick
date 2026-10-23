package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
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
import com.github.uncomplexco.sidekick.application.turn.ParticipationClassifierMessage
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
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class TurnParticipationTest {
    @TempDir lateinit var dir: Path

    @Nested
    inner class BatchParticipation {
        @Test
        fun `ambiguous batch delegates all messages including skipped inputs with authors and shared history`() =
            runBlocking {
                // Arrange
                val config = config()
                val llm = mock(LlmParticipationClassifier::class.java)
                val participation =
                    TurnParticipation(config, DeterministicParticipationClassifier(), llm)
                val messages =
                    listOf(
                        incoming("m1", "thanks", "alice"),
                        incoming("m2", "what do you mean?", "bob"),
                        incoming("m3", "", "alice"),
                    )
                val turn = recordedBatch(config, messages)
                val expectedInput =
                    ParticipationClassifierInput(
                        messages =
                            messages.map { ParticipationClassifierMessage(it.text, it.sender) },
                        botUser = MessageAuthor("sidekick", "Sidekick"),
                        messageHistory = turn.context.conversation.history.messages,
                        hasAssistantHistory = true,
                        conversationId = turn.context.conversation.conversationId,
                    )
                val expectedDecision =
                    TurnParticipationDecision.Respond(TurnParticipationReason.CLASSIFIER)
                `when`(llm.classify(expectedInput)).thenReturn(expectedDecision)

                // Act
                val decision = participation.decide(TurnParticipationInput(messages, turn))

                // Assert
                verify(llm).classify(expectedInput)
                assertEquals(expectedDecision, decision)
            }

        @Test
        fun `earlier explicit mention responds even when last message is an acknowledgment`() =
            runBlocking {
                // Arrange
                val config = config()
                val llm = mock(LlmParticipationClassifier::class.java)
                val participation =
                    TurnParticipation(config, DeterministicParticipationClassifier(), llm)
                val messages =
                    listOf(
                        incoming("m1", "help", type = IncomingMessageType.EXPLICIT_MENTION),
                        incoming("m2", "thanks"),
                    )

                // Act
                val decision =
                    participation.decide(
                        TurnParticipationInput(messages, recordedBatch(config, messages))
                    )

                // Assert
                assertEquals(
                    TurnParticipationDecision.Respond(TurnParticipationReason.EXPLICIT_MENTION),
                    decision,
                )
                verifyNoInteractions(llm)
            }

        @Test
        fun `later unsubscribe is not turned into an explicit mention by another message`() =
            runBlocking {
                // Arrange
                val config = config()
                val llm = mock(LlmParticipationClassifier::class.java)
                val participation =
                    TurnParticipation(config, DeterministicParticipationClassifier(), llm)
                val messages =
                    listOf(
                        incoming("m1", "help", type = IncomingMessageType.EXPLICIT_MENTION),
                        incoming("m2", "unsubscribe"),
                    )

                // Act
                val decision =
                    participation.decide(
                        TurnParticipationInput(messages, recordedBatch(config, messages))
                    )

                // Assert
                assertIs<TurnParticipationDecision.Unsubscribe>(decision)
                verifyNoInteractions(llm)
            }

        @Test
        fun `private batch responds using shared private conversation context`() = runBlocking {
            // Arrange
            val config = config()
            val llm = mock(LlmParticipationClassifier::class.java)
            val participation =
                TurnParticipation(config, DeterministicParticipationClassifier(), llm)
            val messages =
                listOf(
                    incoming("m1", "thanks", type = IncomingMessageType.ASSISTANT_MESSAGE),
                    incoming("m2", "help with this", type = IncomingMessageType.ASSISTANT_MESSAGE),
                )

            // Act
            val decision =
                participation.decide(
                    TurnParticipationInput(messages, recordedBatch(config, messages))
                )

            // Assert
            assertEquals(
                TurnParticipationDecision.Respond(TurnParticipationReason.PRIVATE_MESSAGE),
                decision,
            )
            verifyNoInteractions(llm)
        }
    }

    private fun config() =
        AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
            it.botUsername = "sidekick"
        }

    private fun incoming(
        id: String,
        text: String,
        author: String = "alice",
        type: IncomingMessageType = IncomingMessageType.PASSIVE_MESSAGE,
    ) = IncomingMessage(id, 2, MessageAuthor(author, author), text, type)

    private fun recordedBatch(config: AgentConfig, messages: List<IncomingMessage>): RecordedTurn =
        recordedTurn(config, ConversationId("C123", "thread1"), messages.first()).let { turn ->
            turn.copy(
                context = turn.context.copy(lastMessageId = messages.last().id),
                messages =
                    messages.map {
                        SessionMessage(
                            it.id,
                            SessionMessageRole.USER,
                            it.sender,
                            it.text,
                            createdAtMs = it.createdAtMs,
                        )
                    },
            )
        }

    @Nested
    inner class LlmDelegation {
        @Test
        fun `ambiguous message calls llm classifier with its author and shared context`() =
            runBlocking {
                // Arrange
                val config =
                    AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
                        it.botUsername = "sidekick"
                    }
                val llmClassifier = mock(LlmParticipationClassifier::class.java)
                val participation =
                    TurnParticipation(config, DeterministicParticipationClassifier(), llmClassifier)
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
                        messages =
                            listOf(ParticipationClassifierMessage(message.text, message.sender)),
                        botUser = MessageAuthor("sidekick", "Sidekick"),
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
                val decision = participation.decide(TurnParticipationInput(listOf(message), turn))

                // Assert
                verify(llmClassifier).classify(classifierInput)
                assertEquals(expectedDecision, decision)
            }
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
}
