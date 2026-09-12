package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import org.junit.jupiter.api.Test

class SystemToolsTest {
    @Test
    fun `report assistant activity updates chat activity indicator`() {
        val chat = RecordingChatPlatform()
        val tools = SystemTools(activityReporter = chat.lifecycle)

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
        val tools = SystemTools(clock, RecordingChatPlatform().lifecycle)

        // Act
        val result = tools.currentDateTime()

        // Assert
        assertTrue(result.ok)
        assertEquals(1_780_317_296_000, result.unix_ms)
        assertEquals("2026-06-01T12:34:56Z", result.iso_utc)
    }

    @Test
    fun `timestamp to iso utc treats ten digit values as seconds`() {
        val result =
            SystemTools(activityReporter = RecordingChatPlatform().lifecycle)
                .timestampToIsoUtc(1_780_317_296)

        assertTrue(result.ok)
        assertEquals(1_780_317_296, result.timestamp)
        assertEquals("seconds", result.interpreted_as)
        assertEquals("2026-06-01T12:34:56Z", result.iso_utc)
    }

    @Test
    fun `timestamp to iso utc treats thirteen digit values as milliseconds`() {
        val result =
            SystemTools(activityReporter = RecordingChatPlatform().lifecycle)
                .timestampToIsoUtc(1_780_317_296_123)

        assertTrue(result.ok)
        assertEquals(1_780_317_296_123, result.timestamp)
        assertEquals("milliseconds", result.interpreted_as)
        assertEquals("2026-06-01T12:34:56.123Z", result.iso_utc)
    }
}

private class RecordingChatPlatform : ChatPlatformAdapter {
    override val botUsername = "USIDEKICK"
    val continuedWith = mutableListOf<String?>()
    private val output =
        object : TurnLifecycle, ResponseDelivery {
            override fun begin() = Unit

            override fun progress(text: String?) {
                continuedWith += text
            }

            override fun end() = Unit

            override suspend fun markProcessing(message: IncomingMessage) = Unit

            override suspend fun deliver(
                response: AgentResponse,
                metadata: ResponseMetadata,
            ): PublishedMessage = PublishedMessage("reply", 1)

            override suspend fun markCompleted(message: IncomingMessage) = Unit

            override suspend fun markFailed(message: IncomingMessage) = Unit
        }
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = files
}
