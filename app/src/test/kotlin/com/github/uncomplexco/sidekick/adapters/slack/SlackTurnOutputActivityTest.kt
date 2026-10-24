package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlackTurnOutputActivityTest {
    @Test
    fun `begin alone is invisible and missing thread never publishes`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val scope = SidekickCoroutineScope(backgroundScope)
        val output = SlackTurnOutput(api.client, CHANNEL, ChatThreadId(THREAD, true), scope)
        val withoutThread = SlackTurnOutput(api.client, CHANNEL, null, scope)

        // Act
        output.begin()
        withoutThread.begin()
        withoutThread.progress("Working")
        runCurrent()
        advanceTimeBy(121_000)
        runCurrent()
        withoutThread.end()

        // Assert
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `repeated progress keeps processing without resetting or duplicating renewal`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.progress()
        runCurrent()

        // Act
        advanceTimeBy(2_000)
        output.progress("Compacting conversation...")
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(listOf(0L, 2_000L), api.calls.map { it.time })
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        output.progress()
        advanceTimeBy(8_000)
        runCurrent()

        // Assert
        assertEquals(
            listOf(0L, 2_000L, 8_000L, 16_000L, 16_000L, 24_000L),
            api.calls.map { it.time },
        )
        assertTrue(api.calls.all { it.request.status == "processing" })
    }

    @Test
    fun `rejected and throwing renewals do not prevent later attempts`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.progress("Still working")
        api.beforeStatus = {
            when (testScheduler.currentTime) {
                8_000L -> false
                16_000L -> throw IOException("Temporary failure")
                else -> true
            }
        }
        runCurrent()

        // Act
        advanceTimeBy(121_000)
        runCurrent()

        // Assert
        assertEquals((0L..120_000L step 8_000L).toList(), api.calls.map { it.time })
        assertEquals("processing", api.visibleStatus(CHANNEL, THREAD))
        assertTrue(api.calls.all { it.request.status == "processing" })
    }

    @Test
    fun `end rejects late progress and a new turn starts processing again`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.progress("Old work")
        runCurrent()

        // Act
        output.end()
        output.progress("Too late")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("processing", "active"), api.calls.map { it.request.status })
        output.begin()
        output.progress()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()

        // Assert
        assertEquals(listOf(0L, 0L, 60_000L, 68_000L), api.calls.map { it.time })
        assertEquals("processing", api.calls.last().request.status)
    }

    @Test
    fun `pending cleanup from previous turn cannot clear the next turn activity`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.progress("Old work")
        runCurrent()

        // Act
        output.end()
        output.begin()
        output.progress("New work")
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()

        // Assert
        assertEquals("processing", api.visibleStatus(CHANNEL, THREAD))
        assertEquals(
            listOf("processing", "active", "processing", "processing"),
            api.calls.map { it.request.status },
        )
    }

    @Test
    fun `scope shutdown clears active status and cannot resurrect it`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val scope = SidekickCoroutineScope(backgroundScope)
        val output = SlackTurnOutput(api.client, CHANNEL, ChatThreadId(THREAD, true), scope)
        output.begin()
        output.progress()
        runCurrent()

        // Act
        scope.close()
        runCurrent()
        output.progress("Too late")
        output.begin()
        output.progress("Cancelled scope")
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // Assert
        assertNull(api.visibleStatus(CHANNEL, THREAD))
        assertEquals(1, api.calls.count { it.request.status == "processing" })
        assertEquals("active", api.calls.last().request.status)
    }

    @Test
    fun `already cancelled scope never publishes activity`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val scope = SidekickCoroutineScope(backgroundScope)
        scope.close()
        val output = SlackTurnOutput(api.client, CHANNEL, ChatThreadId(THREAD, true), scope)

        // Act
        output.begin()
        output.progress()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // Assert
        assertNull(api.visibleStatus(CHANNEL, THREAD))
        assertTrue(api.calls.all { it.request.status == "active" })
    }

    @Test
    fun `end clears after an in-flight renewal and old work cannot affect next turn`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        val refreshing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ending = CountDownLatch(1)
        val ended = CountDownLatch(1)
        output.begin()
        output.progress("Old work")
        runCurrent()
        api.beforeStatus = { request ->
            if (request.status == "processing") {
                refreshing.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Renewal release timed out" }
            }
            true
        }

        // Act
        Executors.newFixedThreadPool(2).use { workers ->
            try {
                val refresh = workers.submit {
                    testScheduler.advanceTimeBy(8_000)
                    testScheduler.runCurrent()
                }
                assertTrue(refreshing.await(5, TimeUnit.SECONDS))
                val end = workers.submit {
                    ending.countDown()
                    output.end()
                    ended.countDown()
                }
                assertTrue(ending.await(5, TimeUnit.SECONDS))
                assertFalse(
                    ended.await(100, TimeUnit.MILLISECONDS),
                    "End must wait for the in-flight status request",
                )
                release.countDown()
                refresh.get(5, TimeUnit.SECONDS)
                end.get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
        assertNull(api.visibleStatus(CHANNEL, THREAD))
        api.beforeStatus = { true }
        output.begin()
        output.progress("New work")
        runCurrent()
        advanceTimeBy(16_000)
        runCurrent()

        // Assert
        assertEquals(
            listOf("processing", "processing", "active", "processing", "processing", "processing"),
            api.calls.map { it.request.status },
        )
    }

    companion object {
        private const val CHANNEL = "D123"
        private const val THREAD = "1700000000.000"
    }
}
