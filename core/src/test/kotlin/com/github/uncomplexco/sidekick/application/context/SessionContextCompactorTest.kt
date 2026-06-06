package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionContextCompactorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `does not compact when estimated context is below trigger`() =
        runBlocking {
            // Arrange
            val summarizer = RecordingSummarizer()
            val compactor = compactor(summarizer)
            val state =
                state(
                    messages =
                        (1..20).map {
                            message(id = "m$it", createdAtMs = it.toLong(), text = "short message $it")
                        },
                )

            // Act
            compactor.compactIfNeeded(state)

            // Assert
            assertEquals(0, summarizer.batches.size)
            assertEquals(0, state.compactions.size)
            assertEquals((1..20).map { "m$it" }, state.messages.map { it.id })
        }

    @Test
    fun `compacts oldest batch and keeps latest live messages`() =
        runBlocking {
            // Arrange
            val summarizer = RecordingSummarizer()
            val compactor = compactor(summarizer)
            val state =
                state(
                    messages =
                        (1..37).map {
                            message(id = "m$it", createdAtMs = it.toLong(), text = longText())
                        },
                )

            // Act
            compactor.compactIfNeeded(state)

            // Assert
            assertEquals(listOf((1..24).map { "m$it" }), summarizer.batches)
            assertEquals(1, state.compactions.size)
            assertEquals((1..24).map { "m$it" }, state.compactions.single().coveredMessageIds)
            assertEquals((25..37).map { "m$it" }, state.messages.map { it.id })
        }

    @Test
    fun `compacts repeatedly until target or minimum live messages`() =
        runBlocking {
            // Arrange
            val summarizer = RecordingSummarizer()
            val compactor = compactor(summarizer)
            val state =
                state(
                    messages =
                        (1..60).map {
                            message(id = "m$it", createdAtMs = it.toLong(), text = longText())
                        },
                )

            // Act
            compactor.compactIfNeeded(state)

            // Assert
            assertEquals(
                listOf(
                    (1..24).map { "m$it" },
                    (25..48).map { "m$it" },
                ),
                summarizer.batches,
            )
            assertEquals(2, state.compactions.size)
            assertEquals((49..60).map { "m$it" }, state.messages.map { it.id })
            assertTrue(state.messages.size >= 12)
        }

    @Test
    fun `records assistant message count in compaction`() =
        runBlocking {
            // Arrange
            val summarizer = RecordingSummarizer()
            val compactor = compactor(summarizer)
            val state =
                state(
                    messages =
                        (1..37).map {
                            message(
                                id = "m$it",
                                createdAtMs = it.toLong(),
                                role = if (it % 3 == 0) SessionMessageRole.ASSISTANT else SessionMessageRole.USER,
                                text = longText(),
                            )
                        },
                )

            // Act
            compactor.compactIfNeeded(state)

            // Assert
            assertEquals(8, state.compactions.single().assistantMessageCount)
        }

    private fun compactor(summarizer: RecordingSummarizer): SessionContextCompactor {
        val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
        return SessionContextCompactor(TurnPromptBuilder(config), summarizer)
    }

    private fun state(messages: List<SessionMessage>): ConversationState =
        ConversationState(
            id = ConversationId("C123", "1700000000.000"),
            files = mutableListOf(),
            messages = messages.toMutableList(),
        )

    private fun message(
        id: String,
        createdAtMs: Long,
        role: SessionMessageRole = SessionMessageRole.USER,
        text: String,
    ): SessionMessage =
        SessionMessage(
            id = id,
            role = role,
            author = MessageAuthor(username = "user", fullName = "User"),
            text = text,
            fileIds = emptyList(),
            createdAtMs = createdAtMs,
        )

    private fun longText(): String = "x".repeat(1200)

    private class RecordingSummarizer : SessionContextSummarizer {
        val batches = mutableListOf<List<String>>()

        override suspend fun summarize(
            conversationId: ConversationId,
            messages: List<SessionMessage>,
            files: List<SessionFileRef>,
        ): String {
            batches += messages.map { it.id }
            return "summary for ${messages.first().id}..${messages.last().id}"
        }
    }
}
