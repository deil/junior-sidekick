package com.github.uncomplexco.sidekick.application.scheduling

import com.github.uncomplexco.sidekick.ports.scheduling.ScheduledJobStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduledJobServiceTest {
    @Test
    fun `creates normalized channel job`() =
        runBlocking {
            // Arrange
            val store = TestScheduledJobStore()
            val service = ScheduledJobService(store)

            // Act
            val job =
                service.create(
                    "C123",
                    CreateScheduledJob(
                        name = "  Daily summary ",
                        description = "  Morning report ",
                        schedule = " 0   9  * * 1-5 ",
                        timezone = "Europe/London",
                        prompt = "  Summarize alerts ",
                        enabled = true,
                    ),
                )

            // Assert
            assertEquals(1, job.id)
            assertEquals("Daily summary", job.name)
            assertEquals("Morning report", job.description)
            assertEquals("0 9 * * 1-5", job.schedule)
            assertEquals("Europe/London", job.timezone)
            assertEquals("Summarize alerts", job.prompt)
            assertEquals(listOf(job), store.load("C123"))
        }

    @Test
    fun `enforces unique names and active limit`() =
        runBlocking {
            // Arrange
            val service = ScheduledJobService(TestScheduledJobStore())
            repeat(ACTIVE_JOB_LIMIT) { index ->
                service.create("C123", command(name = "job $index"))
            }

            // Act / Assert
            assertThrows<IllegalArgumentException> {
                runBlocking { service.create("C123", command(name = "JOB 0", enabled = false)) }
            }
            assertThrows<IllegalArgumentException> {
                runBlocking { service.create("C123", command(name = "sixth")) }
            }

            val disabled = service.create("C123", command(name = "disabled", enabled = false))
            assertFalse(disabled.enabled)
            assertThrows<IllegalArgumentException> {
                runBlocking { service.update("C123", disabled.id, UpdateScheduledJob(enabled = true)) }
            }
        }

    @Test
    fun `rejected creation does not consume id`() =
        runBlocking {
            // Arrange
            val service = ScheduledJobService(TestScheduledJobStore())

            // Act
            assertThrows<IllegalArgumentException> {
                runBlocking { service.create("C123", command(name = "invalid", schedule = "not cron")) }
            }
            val created = service.create("C123", command())

            // Assert
            assertEquals(1, created.id)
        }

    @Test
    fun `updates mutable fields and preserves runtime fields`() =
        runBlocking {
            // Arrange
            val store = TestScheduledJobStore()
            val service = ScheduledJobService(store)
            val created = service.create("C123", command(description = "old"))
            store.save("C123", listOf(created.copy(lastRunAt = 123L)))

            // Act
            val updated =
                service.update(
                    "C123",
                    created.id,
                    UpdateScheduledJob(
                        description = "",
                        updateDescription = true,
                        prompt = "new prompt",
                        enabled = false,
                    ),
                )

            // Assert
            assertEquals(created.id, updated.id)
            assertEquals(null, updated.description)
            assertEquals("new prompt", updated.prompt)
            assertFalse(updated.enabled)
            assertEquals(123L, updated.lastRunAt)
        }

    @Test
    fun `claims only matching minute and enforces cooldown`() =
        runBlocking {
            // Arrange
            val store = TestScheduledJobStore()
            val service = ScheduledJobService(store)
            val job = service.create("C123", command(schedule = "0 9 * * *", timezone = "Europe/London"))
            val summerNineAm = Instant.parse("2026-08-17T08:00:20Z")

            // Act
            val first = service.claimDueRuns(summerNineAm)
            val duplicate = service.claimDueRuns(summerNineAm.plusSeconds(20))
            val nextDay = service.claimDueRuns(summerNineAm.plusSeconds(24 * 60 * 60))

            // Assert
            assertEquals(job.id, first.single().job.id)
            assertNotNull(first.single().job.lastRunAt)
            assertTrue(duplicate.isEmpty())
            assertEquals(job.id, nextDay.single().job.id)
        }

    @Test
    fun `cooldown ignores seconds at minute precision`() =
        runBlocking {
            // Arrange
            val store = TestScheduledJobStore()
            val service = ScheduledJobService(store)
            service.create("C123", command(schedule = "0 * * * *"))

            // Act
            val first = service.claimDueRuns(Instant.parse("2026-08-17T09:00:50Z"))
            val nextHour = service.claimDueRuns(Instant.parse("2026-08-17T10:00:01Z"))

            // Assert
            assertEquals(1, first.size)
            assertEquals(1, nextHour.size)
        }

    @Test
    fun `does not catch up missed occurrences`() =
        runBlocking {
            // Arrange
            val service = ScheduledJobService(TestScheduledJobStore())
            service.create("C123", command(schedule = "0 9 * * *", timezone = "UTC"))

            // Act
            val claims = service.claimDueRuns(Instant.parse("2026-08-17T09:01:00Z"))

            // Assert
            assertTrue(claims.isEmpty())
        }

    @Test
    fun `rejects seconds and invalid timezone`() {
        assertThrows<IllegalArgumentException> { normalizeSchedule("0 0 9 * * *") }
        assertThrows<IllegalArgumentException> { normalizeTimezone("Not/AZone") }
    }

    private fun command(
        name: String = "daily",
        description: String? = null,
        schedule: String = "0 9 * * *",
        timezone: String = "UTC",
        prompt: String = "report",
        enabled: Boolean = true,
    ) =
        CreateScheduledJob(
            name = name,
            description = description,
            schedule = schedule,
            timezone = timezone,
            prompt = prompt,
            enabled = enabled,
        )
}

private class TestScheduledJobStore : ScheduledJobStore {
    private val jobs = mutableMapOf<String, List<ScheduledJob>>()
    private val locks = mutableMapOf<String, Mutex>()
    private val sequences = mutableMapOf<String, Int>()

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
