package com.github.uncomplexco.sidekick.application.tools

import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemToolsTest {
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
}
