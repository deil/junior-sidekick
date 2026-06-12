package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import com.slack.api.model.Conversation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull

class SlackChannelToolsTest {
    @Test
    fun `normalizes missing channel limit to default`() {
        assertEquals(200, normalizeSlackChannelLimit(null))
    }

    @Test
    fun `caps large channel limit`() {
        assertEquals(1000, normalizeSlackChannelLimit(5_000))
    }

    @Test
    fun `rejects non-positive channel limit`() {
        assertThrows<ToolException.ValidationFailure> {
            normalizeSlackChannelLimit(0)
        }
    }

    @Test
    fun `normalizes blank query to null`() {
        assertNull(normalizeSlackChannelQuery(null))
        assertNull(normalizeSlackChannelQuery("  "))
        assertNull(normalizeSlackChannelQuery("#"))
    }

    @Test
    fun `normalizes channel query`() {
        assertEquals("platform", normalizeSlackChannelQuery(" #Platform "))
    }

    @Test
    fun `continuation hint tells agent to keep listing`() {
        assertEquals(
            "More channels are available. Call slackChannelsList with cursor=abc to continue.",
            slackChannelContinuationHint(null, "abc"),
        )
    }

    @Test
    fun `continuation hint tells agent to keep searching with query`() {
        assertEquals(
            "Search is page-based and may be incomplete. Call slackChannelsList with query=platform and cursor=abc to continue searching.",
            slackChannelContinuationHint("platform", "abc"),
        )
    }

    @Test
    fun `continuation hint is absent without cursor`() {
        assertNull(slackChannelContinuationHint("platform", null))
    }

    @Test
    fun `formats channels as text with continuation instruction`() {
        val channel =
            Conversation().apply {
                id = "C123"
                nameNormalized = "platform-alerts"
                created = 1_780_317_296
                isPrivate = false
                isArchived = false
                isMember = true
                isShared = false
                numOfMembers = 42
            }

        val output = formatSlackChannels(listOf(channel), "platform", 200, "abc", pagesScanned = 2, channelsScanned = 42)

        assertContains(output, "<slackChannels>")
        assertContains(
            output,
            """<channel id="C123">
name: platform-alerts
created: 2026-06-01T12:34:56Z
private: false""",
        )
        assertContains(output, "Returned 1 matching channel(s); requested limit was 200.")
        assertContains(output, "Scanned 42 Slack channel(s) across 2 page(s).")
        assertContains(output, "Call slackChannelsList with query=platform and cursor=abc to continue searching.")
    }

    @Test
    fun `collects channels across short pages until limit`() {
        val pages =
            mutableListOf(
                SlackChannelPage(listOf(channel("C1", "alpha"), channel("C2", "beta")), "page-2"),
                SlackChannelPage(listOf(channel("C3", "gamma")), null),
            )

        val result = collectSlackChannels(query = null, limit = 3, cursor = null) { _, _ -> pages.removeFirst() }

        assertEquals(listOf("C1", "C2", "C3"), result.channels.map { it.id })
        assertNull(result.nextCursor)
        assertEquals(2, result.pagesScanned)
        assertEquals(3, result.channelsScanned)
    }

    @Test
    fun `keeps scanning after empty search page`() {
        val pages =
            mutableListOf(
                SlackChannelPage(listOf(channel("C1", "alpha")), "page-2"),
                SlackChannelPage(emptyList(), "page-3"),
                SlackChannelPage(listOf(channel("C2", "platform-alerts")), null),
            )

        val result = collectSlackChannels(query = "platform", limit = 1, cursor = null) { _, _ -> pages.removeFirst() }

        assertEquals(listOf("C2"), result.channels.map { it.id })
        assertNull(result.nextCursor)
        assertEquals(3, result.pagesScanned)
        assertEquals(2, result.channelsScanned)
    }

    private fun channel(
        id: String,
        name: String,
    ): Conversation =
        Conversation().apply {
            this.id = id
            this.nameNormalized = name
            this.created = 1_780_317_296
            this.numOfMembers = 1
        }
}
