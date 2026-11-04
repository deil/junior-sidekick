package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordTodoMessageLimitTest {
    @ParameterizedTest
    @ValueSource(ints = [1_999, 2_000])
    fun `todo within the limit is posted unchanged`(length: Int) = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        val text = "x".repeat(length)
        output.begin()

        // Act
        output.reportStatus(text, "todos")

        // Assert
        assertEquals(text, api.messages.getValue("message-1"))
        output.end()
    }

    @Test
    fun `oversized initial todo keeps the footer and marks omitted content`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        val footer = "\n\n_todos as of 12:00:00 (just now)_"
        output.begin()

        // Act
        output.reportStatus("x".repeat(3_000) + footer, "todos")

        // Assert
        val text = api.messages.getValue("message-1")
        assertEquals(2_000, text.length)
        assertTrue(text.endsWith("\n\n… (todo list truncated)$footer"))
        output.end()
    }

    @Test
    fun `oversized edit caps the same message and preserves the completed footer`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        val footer = "\n\n_todos as of 12:01:00_"
        output.begin()
        output.reportStatus("Initial", "todos")

        // Act
        output.reportStatus("x".repeat(3_000) + footer, "todos")

        // Assert
        assertEquals(1, api.messages.size)
        val edit = api.edits.single()
        assertEquals("message-1", edit.first)
        assertEquals(2_000, edit.second.length)
        assertTrue(edit.second.endsWith("\n\n… (todo list truncated)$footer"))
        output.end()
    }

    @Test
    fun `minute refresh applies the cap after rendering the current age`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()
        output.reportStatus("Initial", "todos") {
            "x".repeat(3_000) + "\n\n_todos as of 12:00:00 (1 minute ago)_"
        }
        runCurrent()

        // Act
        advanceTimeBy(60_000)
        runCurrent()

        // Assert
        val text = api.edits.single().second
        assertEquals(2_000, text.length)
        assertTrue(
            text.endsWith("\n\n… (todo list truncated)\n\n_todos as of 12:00:00 (1 minute ago)_")
        )
        output.end()
    }

    @Test
    fun `cap does not split a supplementary character`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        val footer = "\n\n_todos as of 12:00:00 (just now)_"
        output.begin()

        // Act
        output.reportStatus("x".repeat(1_939) + "😀" + "y".repeat(1_000) + footer, "todos")

        // Assert
        assertEquals(
            "x".repeat(1_939) + "\n\n… (todo list truncated)" + footer,
            api.messages.getValue("message-1"),
        )
        output.end()
    }

    @Test
    fun `oversized progress without a todo footer is capped with a notice`() = runTest {
        // Arrange
        val api = RecordingDiscordMessages()
        val output =
            DiscordTurnOutput(
                SidekickCoroutineScope(backgroundScope),
                edit = api::edit,
                send = api::send,
            )
        output.begin()

        // Act
        output.reportStatus("x".repeat(2_001), "todos")

        // Assert
        assertEquals(
            "x".repeat(1_975) + "\n\n… (todo list truncated)",
            api.messages.getValue("message-1"),
        )
        output.end()
    }
}
