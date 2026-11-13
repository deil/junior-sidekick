package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.testing.SlackThreadFixture
import com.github.uncomplexco.sidekick.testing.historyFailure
import com.github.uncomplexco.sidekick.testing.historyPage
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SlackChannelReadToolsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `omitted channel reads the current conversation and empty history succeeds`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        val result = fixture.tools.slackChannelRead()

        // Assert
        assertEquals("C123", fixture.historyRequests.single().channel)
        assertEquals("general", result.channelName)
        assertEquals(0, result.count)
        assertTrue(result.messages.isEmpty())
        assertFalse(result.joinedChannel)
    }

    @ParameterizedTest(name = "channel target {0}")
    @ValueSource(
        strings = ["C456", "<#C456>", "<#C456|platform>", "slack:C456", "platform", "#Platform"]
    )
    fun `target forms resolve to the same known workspace channel`(target: String) {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storeChannel("C456", "platform")
        fixture.storeChannel("C789", "platform", workspaceId = "T456")

        // Act
        val result = fixture.tools.slackChannelRead(channelId = target)

        // Assert
        assertEquals("C456", result.channelId)
        assertEquals("C456", fixture.historyRequests.single().channel)
        assertEquals(listOf("conversationsInfo", "conversationsHistory"), fixture.calls)
    }

    @Test
    fun `unknown channel names are rejected without a live name scan`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(channelId = "unknown") }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `stored private visibility rejects another channel before live lookup`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storePrivate("C456")

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(channelId = "C456") }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `live private channel metadata rejects history and joining`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.channel.isPrivate = true

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(channelId = "C456") }

        // Assert
        assertEquals(listOf("conversationsInfo"), fixture.calls)
    }

    @Test
    fun `current private conversation is readable`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storePrivate("C123")
        fixture.info.channel.isPrivate = true
        fixture.histories += historyPage(count = 1)

        // Act
        val result = fixture.tools.slackChannelRead()

        // Assert
        assertEquals(1, result.count)
        assertTrue(fixture.joinRequests.isEmpty())
    }

    @Test
    fun `time bounds normalize matching references and preserve cursor and inclusive flag`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        fixture.tools.slackChannelRead(
            cursor = "prior-page",
            oldest = "slack:C123:1700000000.000000",
            latest = "1700000100.000000",
            boundsInclusive = true,
        )

        // Assert
        val request = fixture.historyRequests.single()
        assertEquals("prior-page", request.cursor)
        assertEquals("1700000000.000000", request.oldest)
        assertEquals("1700000100.000000", request.latest)
        assertEquals(true, request.isInclusive)
    }

    @Test
    fun `timestamp reference to another channel is rejected before any Slack call`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException> {
            fixture.tools.slackChannelRead(oldest = "slack:C456:1700000000.000000")
        }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @ParameterizedTest(name = "rejects oldest timestamp {0}")
    @ValueSource(strings = ["", " ", "invalid", "1700000000", "slack:C123:invalid"])
    fun `invalid time bounds fail before reading history`(bound: String) {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(oldest = bound) }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `explicit empty channel is not treated as current channel`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(channelId = " ") }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `explicit empty cursor is rejected`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException> { fixture.tools.slackChannelRead(cursor = " ") }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `pages preserve Slack order and do not expand thread replies`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.histories += historyPage(count = 200, cursor = "next")
        fixture.histories += historyPage(count = 200, cursor = "more")

        // Act
        val result = fixture.tools.slackChannelRead(limit = 250)

        // Assert
        assertEquals(250, result.count)
        assertEquals(listOf(200, 50), fixture.historyRequests.map { it.limit })
        assertEquals(listOf(null, "next"), fixture.historyRequests.map { it.cursor })
        assertEquals(listOf("message 0", "message 1"), result.messages.take(2).map { it.text })
        assertEquals("more", result.nextCursor)
        assertTrue(fixture.replyRequests.isEmpty())
    }

    @ParameterizedTest(name = "scan_depth {0} reads {1} pages")
    @CsvSource(value = ["null,10", "99,10", "2,2"], nullValues = ["null"])
    fun `page budget uses shared defaults and caps`(scanDepth: Int?, expected: Int) {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(10) { fixture.histories += historyPage(count = 1, cursor = "next") }

        // Act
        val result = fixture.tools.slackChannelRead(scanDepth = scanDepth)

        // Assert
        assertEquals(expected, result.count)
        assertEquals(expected, fixture.historyRequests.size)
    }

    @Test
    fun `limit is capped at one thousand messages`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(6) { fixture.histories += historyPage(count = 200, cursor = "next") }

        // Act
        val result = fixture.tools.slackChannelRead(limit = 2000)

        // Assert
        assertEquals(1000, result.count)
        assertEquals(5, fixture.historyRequests.size)
    }

    @Test
    fun `joining retries the complete read from its original cursor and bounds`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.channel.isMember = false
        fixture.histories += historyPage(count = 1, cursor = "page-two")
        fixture.histories += historyFailure("not_in_channel")
        fixture.histories += historyPage(count = 2)

        // Act
        val result =
            fixture.tools.slackChannelRead(
                channelId = "C456",
                cursor = "original",
                oldest = "1700000000.000000",
            )

        // Assert
        assertEquals(2, result.count)
        assertTrue(result.joinedChannel)
        assertEquals("C456", fixture.joinRequests.single().channel)
        assertEquals(
            listOf("original", "page-two", "original"),
            fixture.historyRequests.map { it.cursor },
        )
        assertEquals(
            listOf("1700000000.000000", "1700000000.000000", "1700000000.000000"),
            fixture.historyRequests.map { it.oldest },
        )
    }

    @Test
    fun `invalid cursor returns the specified tool error verbatim`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.histories += historyFailure("invalid_cursor")

        // Act
        val error =
            assertThrows<ToolException> { fixture.tools.slackChannelRead(cursor = "expired") }

        // Assert
        assertEquals(
            "The supplied Slack history cursor is no longer valid. " +
                "Retry the lookup without `cursor` to start from the newest page again.",
            error.message,
        )
    }

    @Test
    fun `unknown channel name is omitted from channel response even when encoding defaults`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storedProjects.clear()
        fixture.info.channel = null
        fixture.info.isOk = false

        // Act
        val result = fixture.tools.slackChannelRead()

        // Assert
        assertNull(result.channelName)
        val json = Json { encodeDefaults = true }.encodeToString(result)
        assertFalse("channel_name" in Json.parseToJsonElement(json).jsonObject)
    }
}
