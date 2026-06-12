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

        val output = formatSlackChannels(listOf(channel), "platform", 200, "abc")

        assertContains(output, "<slackChannels>")
        assertContains(output, "1. #platform-alerts | id=C123 | created=2026-06-01T12:34:56Z")
        assertContains(output, "Showing 1 matching channel(s) from one Slack page of up to 200 channel(s).")
        assertContains(output, "Call slackChannelsList with query=platform and cursor=abc to continue searching.")
    }
}
