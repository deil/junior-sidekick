package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SlackUserToolsTest {
    @Test
    fun `requires exactly one search key`() {
        assertThrows<ToolException.ValidationFailure> {
            normalizeSlackUserSearchKeys(null, null, null)
        }
        assertThrows<ToolException.ValidationFailure> {
            normalizeSlackUserSearchKeys("U123", "ada@example.com", null)
        }
        assertEquals(Triple("U123", null, null), normalizeSlackUserSearchKeys(" U123 ", null, null))
    }

    @Test
    fun `caps limit and scan depth`() {
        assertEquals(5, null.normalizedSlackUserLimit())
        assertEquals(25, 100.normalizedSlackUserLimit())
        assertEquals(2, null.normalizedSlackUserScanDepth())
        assertEquals(5, 10.normalizedSlackUserScanDepth())
    }

    @Test
    fun `rejects non-positive limit and scan depth`() {
        assertThrows<ToolException.ValidationFailure> {
            0.normalizedSlackUserLimit()
        }
        assertThrows<ToolException.ValidationFailure> {
            0.normalizedSlackUserScanDepth()
        }
    }
}
