package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.testing.SlackThreadFixture
import com.github.uncomplexco.sidekick.testing.channelPage
import com.github.uncomplexco.sidekick.testing.threadFailure
import com.github.uncomplexco.sidekick.testing.threadPage
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SlackThreadHistoryToolsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `archive markup uses query thread timestamp and reports channel name`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        val result =
            fixture.tools.slackThreadHistory(
                url =
                    "<https://acme.slack.com/archives/C456/p1700000001000000?thread_ts=1700000000.000000|message>"
            )

        // Assert
        assertEquals("C456", fixture.replyRequests.single().channel)
        assertEquals("1700000000.000000", fixture.replyRequests.single().ts)
        assertEquals("1700000000.000000", result.threadTs)
        assertEquals("general", result.channelName)
        assertFalse(result.joinedChannel)
    }

    @Test
    fun `Slack Gov archive child resolves root from the returned message`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.replies += threadPage(threadTs = "1699999999.000000")

        // Act
        val result =
            fixture.tools.slackThreadHistory(
                url = "https://acme.slack-gov.com/archives/C456/p1700000000000001"
            )

        // Assert
        assertEquals("1700000000.000001", fixture.replyRequests.single().ts)
        assertEquals("1699999999.000000", result.threadTs)
    }

    @ParameterizedTest(name = "resolves channel identifier {0}")
    @ValueSource(strings = ["C456", "<#C456>", "<#C456|general>"])
    fun `channel ids and mentions target the specified channel`(channel: String) {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = channel, slackTs = "1700000000.000000")

        // Assert
        assertEquals("C456", result.channelId)
        assertEquals("C456", fixture.replyRequests.single().channel)
    }

    @Test
    fun `channel name resolution scans pages and matches the full name`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.lists += channelPage("general-other", cursor = "next")
        fixture.lists += channelPage("general")

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = "#General", slackTs = "1700000000.000000")

        // Assert
        assertEquals("C456", result.channelId)
        assertEquals(listOf(null, "next"), fixture.listRequests.map { it.cursor })
    }

    @Test
    fun `missing target reports the required argument combinations without calling Slack`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        val error =
            assertThrows<ToolException.ValidationFailure> {
                fixture.tools.slackThreadHistory(slackTs = "1700000000.000000")
            }

        // Assert
        assertEquals(
            "Provide either a Slack message `url` or both `channel_id` and `slack_ts`.",
            error.message,
        )
        assertTrue(fixture.calls.isEmpty())
    }

    @ParameterizedTest(name = "rejects archive URL {0}")
    @ValueSource(
        strings =
            [
                "",
                " ",
                "http://acme.slack.com/archives/C456/p1700000000000000",
                "https://slack.com.evil.test/archives/C456/p1700000000000000",
                "https://acme.slack.com/not-an-archive",
                "https://acme.slack.com/archives/C456/p1700000000000000?thread_ts=bad",
            ]
    )
    fun `invalid explicit URL cannot fall back to a channel and timestamp`(url: String) {
        // Arrange
        val fixture = SlackThreadFixture(dir)

        // Act
        assertThrows<ToolException.ValidationFailure> {
            fixture.tools.slackThreadHistory(
                url = url,
                channelId = "C123",
                slackTs = "1700000000.000000",
            )
        }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `stored private visibility rejects another channel before live lookup`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storePrivate("C456")

        // Act
        assertThrows<ToolException> {
            fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")
        }

        // Assert
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun `current conversation is allowed despite stored and live private visibility`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.storePrivate("C123")
        fixture.info.channel.isPrivate = true

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")

        // Assert
        assertEquals(1, result.count)
        assertTrue(fixture.joinRequests.isEmpty())
    }

    @Test
    fun `live private metadata rejects another channel without reading or joining`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.channel.isPrivate = true

        // Act
        assertThrows<ToolException> {
            fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")
        }

        // Assert
        assertEquals(listOf("conversationsInfo"), fixture.calls)
    }

    @Test
    fun `inaccessible metadata rejects another channel without reading or joining`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.isOk = false
        fixture.info.channel = null
        fixture.info.error = "channel_not_found"

        // Act
        assertThrows<ToolException> {
            fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")
        }

        // Assert
        assertEquals(listOf("conversationsInfo"), fixture.calls)
    }

    @Test
    fun `missing channel metadata does not deny access to the current conversation`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.isOk = false
        fixture.info.channel = null
        fixture.info.error = "missing_scope"

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")

        // Assert
        assertEquals(1, result.count)
        assertTrue(fixture.joinRequests.isEmpty())
    }

    @Test
    fun `nonmember public channel joins after a failed read and retries once`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.channel.isMember = false
        fixture.replies += threadFailure("not_in_channel")
        fixture.replies += threadPage()

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")

        // Assert
        assertTrue(result.joinedChannel)
        assertEquals("C456", fixture.joinRequests.single().channel)
        assertEquals(
            listOf(
                "conversationsInfo",
                "conversationsReplies",
                "conversationsJoin",
                "conversationsReplies",
            ),
            fixture.calls,
        )
    }

    @Test
    fun `known membership failure does not attempt to join`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.replies += threadFailure("not_in_channel")

        // Act
        val error =
            assertThrows<ToolException> {
                fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")
            }

        // Assert
        assertTrue(error.message.orEmpty().contains("not a member"))
        assertTrue(fixture.joinRequests.isEmpty())
        assertEquals(1, fixture.replyRequests.size)
    }

    @Test
    fun `membership read failure after joining is not retried again`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.info.channel.isMember = false
        fixture.replies += threadFailure("not_in_channel")
        fixture.replies += threadFailure("not_in_channel")

        // Act
        assertThrows<ToolException> {
            fixture.tools.slackThreadHistory(channelId = "C456", slackTs = "1700000000.000000")
        }

        // Assert
        assertEquals(1, fixture.joinRequests.size)
        assertEquals(2, fixture.replyRequests.size)
    }

    @Test
    fun `missing history scope returns an actionable tool error`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.replies += threadFailure("missing_scope")

        // Act
        val error =
            assertThrows<ToolException> {
                fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")
            }

        // Assert
        assertTrue(error.message.orEmpty().contains("Missing Slack history scopes"))
        assertTrue(fixture.joinRequests.isEmpty())
    }

    @Test
    fun `empty replies produce a no messages tool error`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.replies += threadPage(count = 0)

        // Act
        val error =
            assertThrows<ToolException> {
                fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")
            }

        // Assert
        assertEquals("No messages found for this thread.", error.message)
    }

    @Test
    fun `message limit is collected in pages of at most two hundred`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(3) { fixture.replies += threadPage(count = 200, cursor = "more") }

        // Act
        val result =
            fixture.tools.slackThreadHistory(
                channelId = "C123",
                slackTs = "1700000000.000000",
                limit = 450,
            )

        // Assert
        assertEquals(450, result.count)
        assertEquals(listOf(200, 200, 50), fixture.replyRequests.map { it.limit })
    }

    @ParameterizedTest(name = "scan_depth {0} stops after {1} pages")
    @CsvSource(value = ["null,10", "99,10", "2,2"], nullValues = ["null"])
    fun `page budget applies the new default and cap`(scanDepth: Int?, expected: Int) {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(10) { fixture.replies += threadPage(cursor = "more") }

        // Act
        val result =
            fixture.tools.slackThreadHistory(
                channelId = "C123",
                slackTs = "1700000000.000000",
                scanDepth = scanDepth,
            )

        // Assert
        assertEquals(expected, result.count)
        assertEquals(expected, fixture.replyRequests.size)
    }

    @Test
    fun `message count is capped at one thousand`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(6) { fixture.replies += threadPage(count = 200, cursor = "more") }

        // Act
        val result =
            fixture.tools.slackThreadHistory(
                channelId = "C123",
                slackTs = "1700000000.000000",
                limit = 2000,
                scanDepth = 10,
            )

        // Assert
        assertEquals(1000, result.count)
        assertEquals(5, fixture.replyRequests.size)
    }

    @Test
    fun `transient connection failure is retried without joining`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        fixture.replies += IOException("connection reset")
        fixture.replies += threadPage()

        // Act
        val result =
            fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")

        // Assert
        assertEquals(1, result.count)
        assertEquals(2, fixture.replyRequests.size)
        assertTrue(fixture.joinRequests.isEmpty())
    }

    @Test
    fun `connection failure stops after three attempts and becomes a tool error`() {
        // Arrange
        val fixture = SlackThreadFixture(dir)
        repeat(3) { fixture.replies += IOException("connection reset") }

        // Act
        val error =
            assertThrows<ToolException> {
                fixture.tools.slackThreadHistory(channelId = "C123", slackTs = "1700000000.000000")
            }

        // Assert
        assertEquals(3, fixture.replyRequests.size)
        assertTrue(error.message.orEmpty().contains("connection error"))
        assertTrue(fixture.joinRequests.isEmpty())
    }
}
