package com.github.uncomplexco.sidekick.application.tools.scheduling

import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobService
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScheduledJobToolsTest {
    @Test
    fun `registers namespaced tool names`() {
        val names = tools(ToolTestScheduledJobStore()).asTools().map { it.name }.toSet()

        assertEquals(
            setOf(
                "scheduled_jobs__create",
                "scheduled_jobs__list",
                "scheduled_jobs__update",
                "scheduled_jobs__delete",
            ),
            names,
        )
    }

    @Test
    fun `creates job with required timezone and channel scope`() {
        // Arrange
        val store = ToolTestScheduledJobStore()
        val tools = tools(store)

        // Act
        val result = tools.createScheduledJob("Daily", null, "0 9 * * *", "Europe/London", "Report", true)

        // Assert
        assertEquals(1, result.job.id)
        assertEquals("Europe/London", result.job.timezone)
        assertEquals(result.job.id, store.load("C123").single().id)
    }

    @Test
    fun `lists updates and deletes by integer id`() {
        // Arrange
        val tools = tools(ToolTestScheduledJobStore())
        val created = tools.createScheduledJob("Daily", "old", "0 9 * * *", "UTC", "Report", true).job

        // Act
        val paused = tools.updateScheduledJob(created.id, description = "", enabled = false).job
        val listed = tools.listScheduledJobs()
        val deleted = tools.deleteScheduledJob(created.id)

        // Assert
        assertFalse(paused.enabled)
        assertEquals(null, paused.description)
        assertEquals(0, listed.active_count)
        assertEquals(created.id, deleted.id)
        assertEquals(emptyList(), tools.listScheduledJobs().jobs)
    }

    private fun tools(store: ScheduledJobStore) = ScheduledJobTools("C123", ScheduledJobService(store))
}

private class ToolTestScheduledJobStore : ScheduledJobStore {
    private val jobs = mutableMapOf<String, List<ScheduledJob>>()
    private val sequences = mutableMapOf<String, Int>()
    private val locks = mutableMapOf<String, Mutex>()

    override fun channelIds(): List<String> = jobs.keys.toList()

    override fun allocateId(channelId: String): Int = sequences.getOrDefault(channelId, 0).plus(1).also { sequences[channelId] = it }

    override fun load(channelId: String): List<ScheduledJob> = jobs[channelId].orEmpty()

    override fun save(
        channelId: String,
        jobs: List<ScheduledJob>,
    ) {
        this.jobs[channelId] = jobs.toList()
    }

    override suspend fun <T> withChannelLock(
        channelId: String,
        block: suspend () -> T,
    ): T = locks.getOrPut(channelId) { Mutex() }.withLock { block() }
}
