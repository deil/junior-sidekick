package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InboundQueueTest {
    @Test
    fun `thread messages in same chat conversation use same batch key`() {
        // Arrange
        val conversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")
        val firstMessage = message(id = "1700000001.000")
        val secondMessage = message(id = "1700000002.000")

        // Act
        val first = batchKeyFor(conversationId, firstMessage)
        val second = batchKeyFor(conversationId, secondMessage)

        // Assert
        assertEquals(first, second)
        assertEquals("1700000000.000", first.threadId)
    }

    @Test
    fun `thread messages in different threads use different batch keys`() {
        // Arrange
        val message = message()

        // Act
        val first = batchKeyFor(ChatConversationId(channelId = "C123", threadId = "1700000000.000"), message)
        val second = batchKeyFor(ChatConversationId(channelId = "C123", threadId = "1700000001.000"), message)

        // Assert
        assertNotEquals(first, second)
    }

    @Test
    fun `root messages in same channel use message id as batch discriminator`() {
        // Arrange
        val conversationId = ChatConversationId(channelId = "C123")
        val firstMessage = message(id = "1700000001.000")
        val secondMessage = message(id = "1700000002.000")

        // Act
        val first = batchKeyFor(conversationId, firstMessage)
        val second = batchKeyFor(conversationId, secondMessage)

        // Assert
        assertNotEquals(first, second)
        assertEquals(firstMessage.id, first.threadId)
        assertEquals(secondMessage.id, second.threadId)
    }

    @Test
    fun `root messages in different channels use different batch keys even with same message id`() {
        // Arrange
        val message = message(id = "1700000001.000")

        // Act
        val first = batchKeyFor(ChatConversationId(channelId = "C123"), message)
        val second = batchKeyFor(ChatConversationId(channelId = "C456"), message)

        // Assert
        assertNotEquals(first, second)
    }

    @Test
    fun `batch keys can be used as map keys`() {
        // Arrange
        val threadConversationId = ChatConversationId(channelId = "C123", threadId = "1700000000.000")
        val rootConversationId = ChatConversationId(channelId = "C123")
        val batches = mutableMapOf<BatchKey, List<String>>()

        // Act
        batches[batchKeyFor(threadConversationId, message(id = "1700000001.000"))] = listOf("first thread message")
        batches[batchKeyFor(threadConversationId, message(id = "1700000002.000"))] = listOf("second thread message")
        batches[batchKeyFor(rootConversationId, message(id = "1700000001.000"))] = listOf("first root message")
        batches[batchKeyFor(rootConversationId, message(id = "1700000002.000"))] = listOf("second root message")

        // Assert
        assertEquals(3, batches.size)
        assertEquals(
            listOf("second thread message"),
            batches[batchKeyFor(threadConversationId, message(id = "1700000003.000"))],
        )
        assertEquals(
            listOf("first root message"),
            batches[batchKeyFor(rootConversationId, message(id = "1700000001.000"))],
        )
        assertEquals(
            listOf("second root message"),
            batches[batchKeyFor(rootConversationId, message(id = "1700000002.000"))],
        )
    }

    private fun message(id: String = "1700000001.000"): InboundMessage =
        InboundMessage(
            id = id,
            createdAtMs = 1,
            sender = MessageAuthor(username = "U123", fullName = "User"),
            text = "hello",
            type = ChatMessageType.PASSIVE_MESSAGE,
        )
}
