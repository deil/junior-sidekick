package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SlackHistoryToolsTest {
    @Test
    fun `normalizes missing limit to default`() {
        assertEquals(100, normalizeSlackHistoryLimit(null))
    }

    @Test
    fun `caps large limit`() {
        assertEquals(200, normalizeSlackHistoryLimit(500))
    }

    @Test
    fun `rejects non-positive limit`() {
        assertThrows<ToolException.ValidationFailure> {
            normalizeSlackHistoryLimit(0)
        }
    }

    @Test
    fun `normalizes missing scan depth to default`() {
        assertEquals(1, normalizeSlackHistoryScanDepth(null))
    }

    @Test
    fun `caps large scan depth`() {
        assertEquals(5, normalizeSlackHistoryScanDepth(10))
    }

    @Test
    fun `rejects non-positive scan depth`() {
        assertThrows<ToolException.ValidationFailure> {
            normalizeSlackHistoryScanDepth(0)
        }
    }

    @Test
    fun `maps invalid cursor away from fallback error`() {
        assert(slackHistoryError("invalid_cursor", "fallback") != "fallback")
    }
}
