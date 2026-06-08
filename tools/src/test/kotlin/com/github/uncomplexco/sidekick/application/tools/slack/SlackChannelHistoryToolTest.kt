package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SlackChannelHistoryToolTest {
    @Test
    fun `normalizes missing limit to default`() {
        assertEquals(100, normalizeLimit(null))
    }

    @Test
    fun `caps large limit`() {
        assertEquals(200, normalizeLimit(500))
    }

    @Test
    fun `rejects non-positive limit`() {
        assertThrows<ToolException.ValidationFailure> {
            normalizeLimit(0)
        }
    }
}
