package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.tools.system.SystemTools
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SystemToolsTest {
    @Test
    fun `report assistant activity updates chat activity indicator`() {
        val activity = RecordingChatActivityIndicator()
        val tools = SystemTools(activity = activity)

        val result = tools.reportAssistantActivity("Reading project files")

        assertTrue(result.ok)
        assertEquals(listOf<String?>("Reading project files"), activity.continuedWith)
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

private class RecordingChatActivityIndicator : ChatActivityIndicator {
    val continuedWith = mutableListOf<String?>()

    override fun start(text: String?) = Unit

    override fun `continue`(text: String?) {
        continuedWith += text
    }

    override fun toolCall(name: String) = Unit

    override fun clear() = Unit

    override fun endTurn() = Unit
}
