package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlackProgressMessagesTest {
    @Test
    fun `message refreshes every minute without posting another message`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(
                api.client,
                "C123",
                "1700000000.000",
                SidekickCoroutineScope(backgroundScope),
            )
        messages.begin()
        messages.report("Initial", "todos") { "Age ${testScheduler.currentTime / 60_000}" }
        runCurrent()

        // Act
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(1, api.replies.size)
        assertEquals(listOf("Age 1", "Age 2"), api.edits.map { it.text })
        assertEquals(listOf("1700000001.000", "1700000001.000"), api.edits.map { it.ts })
    }

    @Test
    fun `finished update cancels refresh and replaces the full message`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.report("Initial", "todos") { "Stale" }
        runCurrent()

        // Act
        messages.report("Completed", "todos", null)
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(listOf("Completed"), api.edits.map { it.text })
    }

    @Test
    fun `new todo call replaces the refresh callback and restarts the minute interval`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.report("Old list", "todos") { "Old refresh" }
        runCurrent()
        advanceTimeBy(30_000)

        // Act
        messages.report("New list", "todos") { "New refresh" }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        // Assert
        assertEquals(listOf("New list", "New refresh"), api.edits.map { it.text })
    }

    @Test
    fun `ending the turn stops message refreshes`() = runTest {
        // Arrange
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.report("Initial", "todos") { "Stale" }
        runCurrent()

        // Act
        messages.end()
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(emptyList(), api.edits)
        assertEquals("Initial", api.replies.single().text)
    }
}
