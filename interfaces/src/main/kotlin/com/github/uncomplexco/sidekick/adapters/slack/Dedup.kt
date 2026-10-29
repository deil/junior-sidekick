package com.github.uncomplexco.sidekick.adapters.slack

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class HandledEventsDeduper(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = 5.minutes,
) {
    private val events = ConcurrentHashMap<String, Long>()

    fun put(
        channelId: String,
        messageId: String,
    ): Boolean {
        cleanupExpired()

        val key = "$channelId:$messageId"
        val expiresAt = clock.millis() + ttl.inWholeMilliseconds
        val alreadySeen = events.putIfAbsent(key, expiresAt)
        return alreadySeen == null
    }

    fun cleanupExpired() {
        val threshold = clock.millis()
        events.entries.removeIf { it.value <= threshold }
    }
}
