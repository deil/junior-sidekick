package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.application.scheduling.CreateScheduledJob
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobRun
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobService
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobDispatcher
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ScheduledJobsSchedulerTest {
    @Test
    fun `claims and dispatches jobs due in current minute`() =
        runBlocking {
            // Arrange
            val store = SchedulerTestJobStore()
            val jobs = ScheduledJobService(store)
            val created =
                jobs.create(
                    "C123",
                    CreateScheduledJob("hourly", null, "* * * * *", "UTC", "report", true),
                )
            val dispatched = mutableListOf<ScheduledJobRun>()
            val dispatcher = ScheduledJobDispatcher(dispatched::add)
            val provider = StaticListableBeanFactory(mapOf("dispatcher" to dispatcher)).getBeanProvider(ScheduledJobDispatcher::class.java)
            val scheduler = ScheduledJobsScheduler(jobs, provider, SidekickCoroutineScope(CoroutineScope(Dispatchers.Unconfined)))

            // Act
            scheduler.tick()

            // Assert
            assertEquals("C123", dispatched.single().channelId)
            assertEquals(created.id, dispatched.single().job.id)
            assertNotNull(store.load("C123").single().lastRunAt)
            Unit
        }
}

private class SchedulerTestJobStore : ScheduledJobStore {
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
