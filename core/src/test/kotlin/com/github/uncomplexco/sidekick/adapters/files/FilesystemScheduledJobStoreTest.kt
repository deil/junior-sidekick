package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesystemScheduledJobStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `stores channel jobs as json lines`() {
        // Arrange
        val store = store()
        val jobs =
            listOf(
                ScheduledJob(1, "one", null, "0 9 * * *", "UTC", "first", true, null),
                ScheduledJob(2, "two", "desc", "0 * * * *", "Europe/London", "second", false, 123L),
            )

        // Act
        store.save("C123", jobs)

        // Assert
        assertEquals(jobs, store.load("C123"))
        assertEquals(listOf("C123"), store.channelIds())
        val lines = Files.readAllLines(dir.resolve("state/slack/channels/C123/jobs.jsonl"))
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"last_run_at\":123"))
    }

    @Test
    fun `deletes file when channel has no jobs`() {
        // Arrange
        val store = store()
        store.save("C123", listOf(ScheduledJob(1, "one", null, "0 9 * * *", "UTC", "first")))

        // Act
        store.save("C123", emptyList())

        // Assert
        assertTrue(store.load("C123").isEmpty())
        assertTrue(store.channelIds().isEmpty())
    }

    @Test
    fun `allocates monotonically increasing ids after deletion`() {
        // Arrange
        val store = store()

        // Act
        val first = store.allocateId("C123")
        val second = store.allocateId("C123")
        store.save("C123", emptyList())
        val third = store.allocateId("C123")

        // Assert
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(3, third)
    }

    private fun store(): FilesystemScheduledJobStore =
        FilesystemScheduledJobStore(
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString()),
        )
}
