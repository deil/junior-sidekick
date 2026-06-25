package com.github.uncomplexco.sidekick.adapters.files

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationEffort
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
    fun `stores conversation effort and defaults to normal`() {
        // Arrange
        val store = store()
        val conversationId = ConversationId("C123", "1700000000.000")

        // Act
        val initial = store.load(conversationId)
        assertEquals(ConversationEffort.NORMAL, initial.effort)

        initial.effort = ConversationEffort.ULTRATHINK
        store.save(conversationId, initial)
        val loaded = store.load(conversationId)

        // Assert
        assertEquals(ConversationEffort.ULTRATHINK, loaded.effort)
    }

    private fun store(): FilesystemConversationStateStore =
        FilesystemConversationStateStore(
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString()),
        )
}
