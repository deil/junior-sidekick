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
                val decision = participation.decide(TurnParticipationInput(message, turn, false))

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
