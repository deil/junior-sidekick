package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SessionManagerCompletionTest {
    @TempDir lateinit var dir: Path
    private lateinit var config: AgentConfig
    private lateinit var store: FilesystemConversationStateStore
    private lateinit var manager: SessionManager
    private val conversationId = ConversationId("C123", "thread1")

    @BeforeEach
    fun setUp() {
        config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString())
        store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        manager =
            SessionManager(
                store,
                VirtualPathsFactory(config, ChatPlatform.SLACK),
                SessionContextCompactor { _, _, _ -> "summary" },
            )
    }

    @Nested
    inner class SuccessfulReplies {
        @Test
        fun `one reply answers every targeted message without changing unrelated outcomes`() =
            runBlocking {
                // Arrange
                record("m1", "m2", "unrelated", "previously-skipped")
                manager.markMessagesSkipped(
                    conversationId,
                    listOf("previously-skipped"),
                    "ACKNOWLEDGMENT",
                )

                // Act
                reply(listOf("m1", "m2"))

                // Assert
                val saved = reload()
                assertEquals(true, saved.message("m1").replied)
                assertEquals(true, saved.message("m2").replied)
                assertNull(saved.message("unrelated").replied)
                assertEquals(false, saved.message("previously-skipped").replied)
                assertEquals("ACKNOWLEDGMENT", saved.message("previously-skipped").skippedReason)
                val response = saved.messages.single { it.role == SessionMessageRole.ASSISTANT }
                assertEquals("reply1", response.id)
                assertEquals("Done.", response.text)
                assertEquals(10L, response.createdAtMs)
                assertEquals(true, response.replied)
            }

        @Test
        fun `batch reply adds execution usage once to existing usage`() = runBlocking {
            // Arrange
            record("m1", "m2", "m3")
            val state = store.load(conversationId)
            state.stats = ConversationStats(consumedInputTokens = 100, consumedOutputTokens = 200)
            store.save(conversationId, state)

            // Act
            reply(listOf("m1", "m2", "m3"))

            // Assert
            val stats = reload().stats
            assertEquals(111L, stats.consumedInputTokens)
            assertEquals(207L, stats.consumedOutputTokens)
            assertEquals(10L, stats.lastCompletedAtMs)
        }

        @Test
        fun `singleton reply still normalizes and persists the assistant response`() = runBlocking {
            // Arrange
            record("m1")

            // Act
            reply(listOf("m1"), text = " \n Done.   All good. \n ")

            // Assert
            val saved = reload()
            assertEquals(true, saved.message("m1").replied)
            assertEquals(
                "Done. All good.",
                saved.messages.single { it.role == SessionMessageRole.ASSISTANT }.text,
            )
        }
    }

    @Nested
    inner class SkippedTurns {
        @ParameterizedTest
        @ValueSource(strings = ["ACKNOWLEDGMENT", "UNSUBSCRIBE_COMMAND", "AGENT_FAILURE"])
        fun `skip unsubscribe and agent failure apply the same outcome to the whole batch`(
            reason: String
        ) = runBlocking {
            // Arrange
            record("m1", "m2", "unrelated")
            val previousStats = reload().stats

            // Act
            manager.markMessagesSkipped(conversationId, listOf("m1", "m2"), reason)

            // Assert
            val saved = reload()
            assertEquals(false, saved.message("m1").replied)
            assertEquals(false, saved.message("m2").replied)
            assertEquals(reason, saved.message("m1").skippedReason)
            assertEquals(reason, saved.message("m2").skippedReason)
            assertNull(saved.message("unrelated").replied)
            assertNull(saved.message("unrelated").skippedReason)
            assertEquals(listOf("m1", "m2", "unrelated"), saved.messages.map { it.id })
            assertEquals(previousStats, saved.stats)
        }

        @Test
        fun `skipping a batch preserves prior replies and their usage`() = runBlocking {
            // Arrange
            record("previous", "m1", "m2")
            reply(listOf("previous"))
            val previousStats = reload().stats

            // Act
            manager.markMessagesSkipped(conversationId, listOf("m1", "m2"), "AGENT_FAILURE")

            // Assert
            val saved = reload()
            assertEquals(true, saved.message("previous").replied)
            assertNull(saved.message("previous").skippedReason)
            assertEquals(false, saved.message("m1").replied)
            assertEquals(false, saved.message("m2").replied)
            assertEquals(
                "Done.",
                saved.messages.single { it.role == SessionMessageRole.ASSISTANT }.text,
            )
            assertEquals(previousStats, saved.stats)
        }

        @Test
        fun `singleton skip still records its reason`() = runBlocking {
            // Arrange
            record("m1")

            // Act
            manager.markMessagesSkipped(conversationId, listOf("m1"), "ACKNOWLEDGMENT")

            // Assert
            val current = reload().messages.single()
            assertEquals(false, current.replied)
            assertEquals("ACKNOWLEDGMENT", current.skippedReason)
        }
    }

    private suspend fun record(vararg ids: String) =
        manager.recordIncomingMessages(
            conversationId = conversationId,
            seedHistory = false,
            historyLoader = { emptyList() },
            messages =
                ids.mapIndexed { index, id ->
                    SessionMessage(
                        id = id,
                        role = SessionMessageRole.USER,
                        author = MessageAuthor("U123", "User"),
                        text = "request $id",
                        createdAtMs = index.toLong(),
                    )
                },
            files = emptyList(),
        )

    private suspend fun reply(messageIds: List<String>, text: String = "Done.") =
        manager.recordAssistantReply(
            conversationId = conversationId,
            text = text,
            replyId = "reply1",
            createdAtMs = 10,
            originalMessageIds = messageIds,
            inputTokensConsumed = 11,
            outputTokensConsumed = 7,
        )

    private fun reload() =
        FilesystemConversationStateStore(config, ChatPlatform.SLACK).load(conversationId)

    private fun ConversationState.message(id: String) = messages.single { it.id == id }
}
