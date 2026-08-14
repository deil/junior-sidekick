package com.github.uncomplexco.sidekick.adapters.slack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WeeklyStatsReporterTest {
    @Test
    fun `formats compact counts`() {
        assertEquals("840", formatCount(840))
        assertEquals("12.4K", formatCount(12_400))
        assertEquals("1.2M", formatCount(1_234_567))
    }
}
