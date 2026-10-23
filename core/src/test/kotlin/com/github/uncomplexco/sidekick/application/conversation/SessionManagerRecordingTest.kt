package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SessionManagerRecordingTest {
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
    inner class Steering {
        @Test
        fun `appending input preserves turn identity history and profile while updating messages files and target`() =
            runBlocking {
                // Arrange
                record(listOf(message("old", 0)))
                val initial = record(listOf(message("m1", 1)), files = listOf(file("f1")))
                val second =
                    message(
                        "m2",
                        2,
                        author = MessageAuthor("bob", "Bob"),
                        fileIds = listOf("f2"),
                        invocation = ExplicitSkillInvocation("review"),
                    )

                // Act
                val updated =
                    manager.appendIncomingMessages(initial, listOf(second), listOf(file("f2")))

                // Assert
                assertEquals(initial.context.turnId, updated.context.turnId)
                assertEquals(initial.context.aiModelProfile, updated.context.aiModelProfile)
                assertEquals(listOf("m1", "m2"), updated.messages.map { it.id })
                assertEquals("m2", updated.context.lastMessageId)
                assertEquals(
                    listOf("old"),
                    updated.context.conversation.history.messages.map { it.id },
                )
                assertEquals(listOf("f1", "f2"), updated.context.sessionFiles.map { it.id })
                assertMessage(second, reload().messages.last())
                assertEquals(listOf("old", "m1", "m2"), reload().messages.map { it.id })
                assertEquals("m1", initial.context.lastMessageId)
            }
    }

    @Nested
    inner class PersistingInput {
        @Test
        fun `records every message with its own author mention files and skill invocation`() =
            runBlocking {
                // Arrange
                val first =
                    message(
                        "m1",
                        1,
                        fileIds = listOf("f1"),
                        explicitMention = true,
                        invocation = ExplicitSkillInvocation("code-review"),
                    )
                val second =
                    message(
                        "m2",
                        2,
                        author = MessageAuthor("U456", "Other user"),
                        fileIds = listOf("f2"),
                    )

                // Act
                val turn = record(listOf(first, second), files = listOf(file("f1"), file("f2")))

                // Assert
                val saved = reload()
                assertEquals(listOf("m1", "m2"), saved.messages.map { it.id })
                assertMessage(first, saved.messages[0])
                assertMessage(second, saved.messages[1])
                assertEquals(listOf("m1", "m2"), turn.messages.map { it.id })
                assertEquals(listOf("f1", "f2"), saved.files.map { it.id })
                assertEquals(saved.files, turn.context.sessionFiles)
                assertEquals(file("f1").localPath, saved.files[0].localPath)
                assertEquals(file("f2").summary, saved.files[1].summary)
            }

        @Test
        fun `returns and persists batch in chronological order with latest message as target`() =
            runBlocking {
                // Arrange
                val first = message("m1", 1)
                val second = message("m2", 2)
                val third = message("m3", 3)

                // Act
                val turn = record(listOf(third, first, second))

                // Assert
                assertEquals(listOf("m1", "m2", "m3"), turn.messages.map { it.id })
                assertEquals(listOf("m1", "m2", "m3"), reload().messages.map { it.id })
                assertEquals("m3", turn.context.lastMessageId)
            }

        @Test
        fun `recording the same batch again updates messages without duplicate messages or files`() =
            runBlocking {
                // Arrange
                record(listOf(message("m1", 1), message("m2", 2)), files = listOf(file("f1")))
                val updated = message("m2", 2, text = "corrected request", fileIds = listOf("f1"))

                // Act
                record(listOf(message("m1", 1), updated), files = listOf(file("f1")))

                // Assert
                val saved = reload()
                assertEquals(listOf("m1", "m2"), saved.messages.map { it.id })
                assertMessage(updated, saved.messages[1])
                assertEquals(listOf("f1"), saved.files.map { it.id })
            }

        @Test
        fun `singleton input remains one recorded message and is its own target`() = runBlocking {
            // Arrange
            val current = message("m1", 1)

            // Act
            val turn = record(listOf(current))

            // Assert
            assertMessage(current, turn.messages.single())
            assertMessage(current, reload().messages.single())
            assertEquals("m1", turn.context.lastMessageId)
            assertTrue(turn.context.conversation.history.messages.isEmpty())
        }
    }

    @Nested
    inner class BuildingPriorContext {
        @Test
        fun `prior history retains existing messages but excludes every current batch message`() =
            runBlocking {
                // Arrange
                val old = message("old", 1)
                record(listOf(old), files = listOf(file("old-file")))
                val first = message("m1", 2)
                val second = message("m2", 3)

                // Act
                val turn = record(listOf(first, second), files = listOf(file("new-file")))

                // Assert
                assertEquals(
                    listOf("old"),
                    turn.context.conversation.history.messages.map { it.id },
                )
                assertEquals(listOf("old", "m1", "m2"), reload().messages.map { it.id })
                assertEquals(
                    listOf("old-file", "new-file"),
                    turn.context.sessionFiles.map { it.id },
                )
                assertEquals(conversationId, turn.context.conversation.conversationId)
            }

        @Test
        fun `recording retains the session model and subscription setting`() = runBlocking {
            // Arrange
            val state = store.load(conversationId)
            state.aiModel = AiModelProfile.ULTRATHINK
            state.subscribed = false
            store.save(conversationId, state)

            // Act
            val turn = record(listOf(message("m1", 1), message("m2", 2)))

            // Assert
            assertEquals(AiModelProfile.ULTRATHINK, turn.context.aiModelProfile)
            assertEquals(AiModelProfile.ULTRATHINK, reload().aiModel)
            assertEquals(false, reload().subscribed)
        }
    }

    @Nested
    inner class SeedingHistory {
        @Test
        fun `live batch replaces seeded copies and excludes all batch ids from prior context`() =
            runBlocking {
                // Arrange
                val first =
                    message(
                        "m1",
                        2,
                        explicitMention = true,
                        invocation = ExplicitSkillInvocation("code-review"),
                    )
                val second = message("m2", 3, text = "live correction")
                val history =
                    listOf(chatMessage("m2", 3), chatMessage("old", 1), chatMessage("m1", 2))

                // Act
                val turn = record(listOf(first, second), seedHistory = true, history = history)

                // Assert
                val saved = reload()
                assertEquals(listOf("old", "m1", "m2"), saved.messages.map { it.id })
                assertMessage(first, saved.messages[1])
                assertMessage(second, saved.messages[2])
                assertEquals(
                    listOf("old"),
                    turn.context.conversation.history.messages.map { it.id },
                )
                assertEquals(listOf("m1", "m2"), turn.messages.map { it.id })
            }

        @Test
        fun `existing session history remains authoritative when seeding is requested`() =
            runBlocking {
                // Arrange
                record(listOf(message("old", 1)))

                // Act
                val turn =
                    record(
                        listOf(message("m1", 2), message("m2", 3)),
                        seedHistory = true,
                        history = listOf(chatMessage("platform-only", 1)),
                    )

                // Assert
                assertEquals(listOf("old", "m1", "m2"), reload().messages.map { it.id })
                assertEquals(
                    listOf("old"),
                    turn.context.conversation.history.messages.map { it.id },
                )
            }

        @Test
        fun `disabled seeding records only the live batch`() = runBlocking {
            // Arrange
            val inputs = listOf(message("m1", 2), message("m2", 3))

            // Act
            val turn = record(inputs, history = listOf(chatMessage("platform-only", 1)))

            // Assert
            assertEquals(listOf("m1", "m2"), reload().messages.map { it.id })
            assertTrue(turn.context.conversation.history.messages.isEmpty())
        }
    }

    private suspend fun record(
        messages: List<SessionMessage>,
        files: List<IncomingChatFile> = emptyList(),
        seedHistory: Boolean = false,
        history: List<ChatMessage> = emptyList(),
    ) =
        manager.recordIncomingMessages(
            conversationId = conversationId,
            seedHistory = seedHistory,
            historyLoader = { history },
            messages = messages,
            files = files,
        )

    private fun reload() =
        FilesystemConversationStateStore(config, ChatPlatform.SLACK).load(conversationId)

    private fun message(
        id: String,
        createdAtMs: Long,
        text: String = "request $id",
        author: MessageAuthor = MessageAuthor("U123", "User"),
        fileIds: List<String> = emptyList(),
        explicitMention: Boolean = false,
        invocation: ExplicitSkillInvocation? = null,
    ) =
        SessionMessage(
            id = id,
            role = SessionMessageRole.USER,
            author = author,
            text = text,
            fileIds = fileIds,
            createdAtMs = createdAtMs,
            explicitMention = explicitMention,
            explicitSkillInvocation = invocation,
        )

    private fun chatMessage(id: String, timestamp: Long) =
        ChatMessage(
            id,
            SessionMessageRole.USER,
            MessageAuthor("U123", "User"),
            "platform copy $id",
            timestamp,
            emptyList(),
        )

    private fun file(id: String) =
        IncomingChatFile(
            id = id,
            name = "$id.txt",
            mimetype = "text/plain",
            filetype = "txt",
            urlPrivateDownload = "https://files.example/$id",
            permalink = "https://chat.example/$id",
            localPath = "/data/session/attachments/$id.txt",
            summary = "contents of $id",
        )

    private fun assertMessage(expected: SessionMessage, actual: SessionMessage) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.role, actual.role)
        assertEquals(expected.author, actual.author)
        assertEquals(expected.text, actual.text)
        assertEquals(expected.fileIds, actual.fileIds)
        assertEquals(expected.createdAtMs, actual.createdAtMs)
        assertEquals(expected.explicitMention, actual.explicitMention)
        assertEquals(expected.explicitSkillInvocation, actual.explicitSkillInvocation)
    }
}
