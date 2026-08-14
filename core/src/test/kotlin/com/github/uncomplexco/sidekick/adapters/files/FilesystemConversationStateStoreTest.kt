package com.github.uncomplexco.sidekick.adapters.files

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationStats
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class FilesystemConversationStateStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `stores koog messages as json lines`() {
        // Arrange
        val store = store()
        val conversationId = ConversationId("C123", "1700000000.000")
        val requestMetaInfo = RequestMetaInfo(Instant.parse("2026-01-01T00:00:00Z"))
        val responseMetaInfo = ResponseMetaInfo(Instant.parse("2026-01-01T00:00:01Z"))
        val messages =
            listOf(
                Message.User("hello", requestMetaInfo),
                Message.Assistant(MessagePart.Tool.Call("call-1", "lookup", """{"query":"sidekick"}"""), responseMetaInfo),
                Message.User(MessagePart.Tool.Result("call-1", "lookup", """{"result":"found"}"""), requestMetaInfo),
                Message.Assistant("done", responseMetaInfo),
            )

        // Act
        val state = store.load(conversationId)
        state.koogMessages = messages.toMutableList()
        store.save(conversationId, state)
        val loaded = store.load(conversationId).koogMessages

        // Assert
        assertEquals(4, loaded.size)
        assertIs<Message.User>(loaded[0])
        assertIs<MessagePart.Tool.Call>(loaded[1].parts.single())
        assertIs<MessagePart.Tool.Result>(loaded[2].parts.single())
        assertIs<Message.Assistant>(loaded[3])
        assertEquals(messages, loaded)

        val koogFile = dir.resolve("state/slack/channels/C123/threads/1700000000.000/koog.jsonl")
        assertEquals(4, Files.readAllLines(koogFile).size)
    }

    @Test
    fun `stores conversation intelligence level and defaults to normal`() {
        // Arrange
        val store = store()
        val conversationId = ConversationId("C123", "1700000000.000")

        // Act
        val initial = store.load(conversationId)
        assertEquals(AiModelProfile.NORMAL, initial.aiModel)

        initial.aiModel = AiModelProfile.ULTRATHINK
        store.save(conversationId, initial)
        val loaded = store.load(conversationId)

        // Assert
        assertEquals(AiModelProfile.ULTRATHINK, loaded.aiModel)
    }

    @Test
    fun `stores conversation subscription and defaults to subscribed`() {
        // Arrange
        val store = store()
        val conversationId = ConversationId("C123", "1700000000.000")

        // Act
        val initial = store.load(conversationId)
        assertEquals(true, initial.subscribed)

        initial.subscribed = false
        store.save(conversationId, initial)
        val loaded = store.load(conversationId)

        // Assert
        assertEquals(false, loaded.subscribed)
    }

    @Test
    fun `stores conversation stats in stats json`() {
        // Arrange
        val store = store()
        val conversationId = ConversationId("C123", "1700000000.000")

        // Act
        val state = store.load(conversationId)
        state.stats = ConversationStats(totalTokens = 123, messages = 4, toolCalls = 2)
        store.save(conversationId, state)
        val loaded = store.load(conversationId)

        // Assert
        assertEquals(123, loaded.stats.totalTokens)
        assertEquals(4, loaded.stats.messages)
        assertEquals(2, loaded.stats.toolCalls)
        assertEquals(true, Files.exists(dir.resolve("state/slack/channels/C123/threads/1700000000.000/stats.json")))
        assertEquals(false, Files.exists(dir.resolve("state/slack/channels/C123/threads/1700000000.000/inflight.json")))
    }

    @Test
    fun `loads conversations created within a period`() {
        // Arrange
        val store = store()
        val threadId = ConversationId("C123", "1700000000.000")
        val state = store.load(threadId)
        state.messages +=
            SessionMessage(
                id = "message",
                role = SessionMessageRole.USER,
                text = "hello",
                createdAtMs = 1,
            )
        store.save(threadId, state)
        Files.createDirectories(dir.resolve("state/slack/channels/C789/threads/empty"))
        val threadFolder = dir.resolve("state/slack/channels/C123/threads/1700000000.000")
        val createdAt = Files.readAttributes(threadFolder, BasicFileAttributes::class.java).creationTime().toMillis()

        // Act
        val included = store.loadStartedBetween(createdAt, createdAt + 1)
        val excludedAtEnd = store.loadStartedBetween(createdAt - 1, createdAt)

        // Assert
        assertEquals(listOf(threadId), included.map { it.id })
        assertEquals(emptyList(), excludedAtEnd)
    }

    private fun store(): FilesystemConversationStateStore =
        FilesystemConversationStateStore(
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString()),
        )
}
