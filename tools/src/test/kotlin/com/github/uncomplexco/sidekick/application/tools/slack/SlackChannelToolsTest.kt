package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
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
}
