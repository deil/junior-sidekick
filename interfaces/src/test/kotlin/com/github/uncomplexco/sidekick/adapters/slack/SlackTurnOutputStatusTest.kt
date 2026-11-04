package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SlackTurnOutputStatusTest {
    private companion object {
        const val CHANNEL = "C123"
        const val THREAD = "1700000000.000"
        const val STATUS_TS = "1700000001.000"
    }

    @Test
    fun `todo updates replace their own message independently of assistant status`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.reportStatus("Investigating")
        output.reportStatus("*Todo*\n• [pending] [high] Fix bug", key = "todos")

        // Act
        output.reportStatus("*Todo*\n• [completed] [high] Fix bug", key = "todos")

        // Assert
        assertEquals(2, api.replies.size)
        assertEquals("Investigating", api.replies.first().text)
        assertEquals("1700000002.000", api.edits.single().ts)
        assertEquals("*Todo*\n• [completed] [high] Fix bug", api.edits.single().text)
        assertEquals(listOf(THREAD, THREAD), api.replies.map { it.threadTs })
        output.end()
    }

    @Test
    fun `rejected initial post allows the next invocation to create the message`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(api.client, CHANNEL, null, SidekickCoroutineScope(backgroundScope))
        output.begin()
        api.rejectPosts = true
        output.reportStatus("Searching")
        api.rejectPosts = false

        // Act
        output.reportStatus("Running tests")

        // Assert
        assertEquals(listOf("Searching", "Running tests"), api.replies.map { it.text })
        assertTrue(api.replies.all { it.threadTs == null })
        assertTrue(api.edits.isEmpty())
        output.end()
    }

    @Test
    fun `post transport failure allows the next invocation to create the message`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(api.client, CHANNEL, null, SidekickCoroutineScope(backgroundScope))
        output.begin()
        api.postError = IOException("Unavailable")
        output.reportStatus("Searching")
        api.postError = null

        // Act
        output.reportStatus("Running tests")

        // Assert
        assertEquals("Running tests", api.replies.single().text)
        assertTrue(api.edits.isEmpty())
        output.end()
    }

    @Test
    fun `rejected edit is ignored and later edit replaces the full text`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(api.client, CHANNEL, null, SidekickCoroutineScope(backgroundScope))
        output.begin()
        output.reportStatus("Searching")
        api.rejectEdits = true
        output.reportStatus("Reading files")
        api.rejectEdits = false

        // Act
        output.reportStatus("Running tests")

        // Assert
        assertEquals(1, api.replies.size)
        assertEquals(listOf("Reading files", "Running tests"), api.edits.map { it.text })
        assertEquals(listOf(STATUS_TS, STATUS_TS), api.edits.map { it.ts })
        output.end()
    }

    @Test
    fun `edit transport failure is ignored and later update edits the same message`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(api.client, CHANNEL, null, SidekickCoroutineScope(backgroundScope))
        output.begin()
        output.reportStatus("Searching")
        api.editError = IOException("Unavailable")
        output.reportStatus("Reading files")
        api.editError = null

        // Act
        output.reportStatus("Running tests")

        // Assert
        assertEquals(1, api.replies.size)
        assertEquals("Running tests", api.edits.single().text)
        assertEquals(STATUS_TS, api.edits.single().ts)
        output.end()
    }

    @Test
    fun `first status posts in the existing thread and later status edits it`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.reportStatus("Searching files")

        // Act
        output.reportStatus("Running tests")

        // Assert
        assertEquals("Searching files", api.replies.single().text)
        assertEquals(THREAD, api.replies.single().threadTs)
        assertEquals(CHANNEL, api.edits.single().channel)
        assertEquals(STATUS_TS, api.edits.single().ts)
        assertEquals("Running tests", api.edits.single().text)
        output.end()
    }

    @Test
    fun `status starts a thread when there is no reply thread and final response follows it`() =
        runTest {
            // Arrange
            val api = SlackActivityApi { 0 }
            val output =
                SlackTurnOutput(api.client, CHANNEL, null, SidekickCoroutineScope(backgroundScope))
            output.begin()
            output.reportStatus("Investigating")

            // Act
            output.deliver(AgentResponse("Done"), ResponseMetadata.None)
            output.end()

            // Assert
            assertEquals(listOf("Investigating", "Done"), api.replies.map { it.text })
            assertNull(api.replies.first().threadTs)
            assertEquals(STATUS_TS, api.replies.last().threadTs)
            assertTrue(api.edits.isEmpty())
        }

    @Test
    fun `status starts a reply under an unthreaded incoming message`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, false),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()

        // Act
        output.reportStatus("Investigating")

        // Assert
        assertEquals(THREAD, api.replies.single().threadTs)
        output.end()
    }

    @Test
    fun `ending leaves status untouched and ignores late updates`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.reportStatus("Investigating")

        // Act
        output.end()
        output.reportStatus("Too late")

        // Assert
        assertEquals("Investigating", api.replies.single().text)
        assertTrue(api.edits.isEmpty())
    }

    @Test
    fun `new turn posts a separate status message`() = runTest {
        // Arrange
        val api = SlackActivityApi { 0 }
        val output =
            SlackTurnOutput(
                api.client,
                CHANNEL,
                ChatThreadId(THREAD, true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.reportStatus("First turn")
        output.end()
        output.begin()

        // Act
        output.reportStatus("Second turn")

        // Assert
        assertEquals(listOf("First turn", "Second turn"), api.replies.map { it.text })
        assertTrue(api.edits.isEmpty())
        output.end()
    }
}
