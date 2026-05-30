package com.github.uncomplexco.sidekick.adapters.slack

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class HandledEventsDeduperTest {
    private val clock = MutableClock()

    @Test
    fun `accepts an event the first time it is seen`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)

        val result = deduper.put(channelId = "C123", messageId = "1700000000.001")

        assertTrue(result)
    }

    @Test
    fun `rejects the same event while it is inside ttl`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)
        deduper.put(channelId = "C123", messageId = "1700000000.001")
        clock.advanceMillis(4.minutes.inWholeMilliseconds)

        val result = deduper.put(channelId = "C123", messageId = "1700000000.001")

        assertFalse(result)
    }

    @Test
    fun `accepts the same event again after ttl expires`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)
        deduper.put(channelId = "C123", messageId = "1700000000.001")
        clock.advanceMillis(5.minutes.inWholeMilliseconds)

        val result = deduper.put(channelId = "C123", messageId = "1700000000.001")

        assertTrue(result)
    }

    @Test
    fun `treats expiry boundary as expired`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)
        deduper.put(channelId = "C123", messageId = "1700000000.001")

        clock.advanceMillis(5.minutes.inWholeMilliseconds - 1)
        val beforeBoundaryResult = deduper.put(channelId = "C123", messageId = "1700000000.001")

        clock.advanceMillis(1)
        val atBoundaryResult = deduper.put(channelId = "C123", messageId = "1700000000.001")

        assertFalse(beforeBoundaryResult)
        assertTrue(atBoundaryResult)
    }

    @Test
    fun `does not deduplicate different message ids in the same channel`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)

        val firstResult = deduper.put(channelId = "C123", messageId = "1700000000.001")
        val secondResult = deduper.put(channelId = "C123", messageId = "1700000001.002")

        assertTrue(firstResult)
        assertTrue(secondResult)
    }

    @Test
    fun `does not deduplicate same message id across different channels`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)

        val firstChannelResult = deduper.put(channelId = "C123", messageId = "1700000000.001")
        val secondChannelResult = deduper.put(channelId = "C456", messageId = "1700000000.001")

        assertTrue(firstChannelResult)
        assertTrue(secondChannelResult)
    }

    @Test
    fun `does not deduplicate direct message channel ids against public channel ids`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)

        val directMessageResult = deduper.put(channelId = "D123", messageId = "1700000000.001")
        val publicChannelResult = deduper.put(channelId = "C123", messageId = "1700000000.001")

        assertTrue(directMessageResult)
        assertTrue(publicChannelResult)
    }

    @Test
    fun `cleanupExpired removes expired events without removing live events`() {
        val deduper = HandledEventsDeduper(clock = clock, ttl = 5.minutes)
        deduper.put(channelId = "C123", messageId = "1700000000.001")
        clock.advanceMillis(4.minutes.inWholeMilliseconds)
        deduper.put(channelId = "C123", messageId = "1700000001.002")

        clock.advanceMillis(1.minutes.inWholeMilliseconds)
        deduper.cleanupExpired()
        val expiredEventResult = deduper.put(channelId = "C123", messageId = "1700000000.001")
        val liveEventResult = deduper.put(channelId = "C123", messageId = "1700000001.002")

        assertTrue(expiredEventResult)
        assertFalse(liveEventResult)
    }
}

private class MutableClock(
    private var currentInstant: Instant = Instant.parse("2026-05-30T12:00:00Z"),
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = currentInstant

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    fun advanceMillis(milliseconds: Long) {
        currentInstant = currentInstant.plusMillis(milliseconds)
    }
}
