package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordTodoMessagesTest {
    @Test
    fun `todo posts once edits the message and remains separate from the final response`() =
        runTest {
            // Arrange
            val api = RecordingDiscordMessages()
            val output =
                DiscordTurnOutput(
                    SidekickCoroutineScope(backgroundScope),
                    edit = api::edit,
                    send = api::send,
                )
            output.begin()
            output.reportStatus("Goal\n\n◦ Fix bug\n\n_todos as of 12:00:00 (just now)_", "todos")

            // Act
            output.reportStatus("Goal\n\n✓ Fix bug\n\n_todos as of 12:01:00_", "todos")
            val response = output.deliver(AgentResponse("Fixed"))
            output.end()

            // Assert
            assertEquals(
                mapOf(
                    "message-1" to "Goal\n\n✓ Fix bug\n\n_todos as of 12:01:00_",
                    "message-2" to "Fixed",
                ),
                api.messages,
            )
            assertEquals("message-2", response.messageId)
            assertEquals(listOf("message-1"), api.edits.map { it.first })
        }

    @Test
    fun `todo refreshes the same message every minute`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("Initial", "todos") { "Age ${testScheduler.currentTime / 60_000}" }
        runCurrent()

        // Act
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(mapOf("message-1" to "Age 2"), api.messages)
        assertEquals(listOf("message-1" to "Age 1", "message-1" to "Age 2"), api.edits)
        output.end()
    }

    @Test
    fun `finished todo stops refreshing`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("Initial", "todos") { "Stale" }
        runCurrent()

        // Act
        output.reportStatus("Finished", "todos")
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(mapOf("message-1" to "Finished"), api.messages)
        assertEquals(listOf("message-1" to "Finished"), api.edits)
        output.end()
    }

    @Test
    fun `turn end stops refreshes and ignores late updates`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("Initial", "todos") { "Stale" }
        runCurrent()

        // Act
        output.end()
        output.reportStatus("Too late", "todos")
        advanceTimeBy(120_000)
        runCurrent()

        // Assert
        assertEquals(mapOf("message-1" to "Initial"), api.messages)
        assertEquals(emptyList(), api.edits)
    }

    @Test
    fun `new turn gets a separate todo message`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("First turn", "todos")
        output.end()
        output.begin()

        // Act
        output.reportStatus("Second turn", "todos")

        // Assert
        assertEquals(mapOf("message-1" to "First turn", "message-2" to "Second turn"), api.messages)
        assertEquals(emptyList(), api.edits)
        output.end()
    }

    @Test
    fun `failed initial post is ignored and next call creates the todo message`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        api.failSend = true
        output.reportStatus("Old list", "todos")
        api.failSend = false

        // Act
        output.reportStatus("Current list", "todos")

        // Assert
        assertEquals(mapOf("message-1" to "Current list"), api.messages)
        assertEquals(emptyList(), api.edits)
        output.end()
    }

    @Test
    fun `failed edit is ignored and later call edits the same todo message`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("Initial", "todos")
        api.failEdit = true
        output.reportStatus("Failed edit", "todos")
        api.failEdit = false

        // Act
        output.reportStatus("Current list", "todos")

        // Assert
        assertEquals(mapOf("message-1" to "Current list"), api.messages)
        assertEquals(listOf("message-1" to "Current list"), api.edits)
        output.end()
    }
}
