package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SystemToolsTest {
    @Test
    fun `report assistant activity updates chat activity indicator`() {
        val chat = RecordingChatPlatform()
        val tools = SystemTools(chat = chat)

        val result = tools.reportAssistantActivity("Reading project files")

        assertTrue(result.ok)
        assertEquals(listOf<String?>("Reading project files"), chat.continuedWith)
    }

    @Test
    fun `current date time returns utc time for configured clock`() {
        // Arrange
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-06-01T12:34:56Z")
            }
        val tools = SystemTools(clock)

        // Act
        val result = tools.currentDateTime()

        // Assert
        assertTrue(result.ok)
        assertEquals(1_780_317_296_000, result.unix_ms)
        assertEquals("2026-06-01T12:34:56Z", result.iso_utc)
    }

    @Test
    fun `timestamp to iso utc treats ten digit values as seconds`() {
        val result = SystemTools().timestampToIsoUtc(1_780_317_296)

        assertTrue(result.ok)
        assertEquals(1_780_317_296, result.timestamp)
        assertEquals("seconds", result.interpreted_as)
        assertEquals("2026-06-01T12:34:56Z", result.iso_utc)
    }

    @Test
    fun `timestamp to iso utc treats thirteen digit values as milliseconds`() {
        val result = SystemTools().timestampToIsoUtc(1_780_317_296_123)

        assertTrue(result.ok)
        assertEquals(1_780_317_296_123, result.timestamp)
        assertEquals("milliseconds", result.interpreted_as)
        assertEquals("2026-06-01T12:34:56.123Z", result.iso_utc)
    }
}

private class RecordingChatPlatform : ChatPlatformAdapter {
    override val botUsername = "USIDEKICK"
    val continuedWith = mutableListOf<String?>()
    override val activity =
        object : TurnActivityIndicator {
            override fun start(text: String?) = Unit

            override fun `continue`(text: String?) {
                continuedWith += text
            }

            override fun toolCall(name: String) = Unit

            override fun clear() = Unit

            override fun endTurn() = Unit
        }

    override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

    override suspend fun postReply(text: String): ReplyResult = ReplyResult("reply", 1)

    override fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = files

}
