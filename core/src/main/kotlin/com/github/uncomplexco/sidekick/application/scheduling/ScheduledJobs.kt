package com.github.uncomplexco.sidekick.application.scheduling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val ACTIVE_JOB_LIMIT = 5
const val JOB_COOLDOWN_MILLIS = 60 * 60 * 1000L

@Serializable
data class ScheduledJob(
    val id: Int,
    val name: String,
    val description: String? = null,
    val schedule: String,
    val timezone: String,
    val prompt: String,
    val enabled: Boolean = true,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
)

data class ScheduledJobRun(
    val channelId: String,
    val job: ScheduledJob,
)

data class CreateScheduledJob(
    val name: String,
    val description: String?,
    val schedule: String,
    val timezone: String,
    val prompt: String,
    val enabled: Boolean,
)

data class UpdateScheduledJob(
    val name: String? = null,
    val description: String? = null,
    val updateDescription: Boolean = false,
    val schedule: String? = null,
    val timezone: String? = null,
    val prompt: String? = null,
    val enabled: Boolean? = null,
) {
    fun hasChanges(): Boolean =
        name != null || updateDescription || schedule != null || timezone != null || prompt != null || enabled != null
}

@Component
class ScheduledJobService(
    private val store: ScheduledJobStore,
) {
    fun list(channelId: String): List<ScheduledJob> = store.load(channelId).sortedBy { it.name.lowercase() }

    suspend fun create(
        channelId: String,
        command: CreateScheduledJob,
    ): ScheduledJob =
        store.withChannelLock(channelId) {
            val jobs = store.load(channelId).toMutableList()
            val name = command.name.cleanRequired("name")
            val description = command.description.cleanOptional()
            val schedule = normalizeSchedule(command.schedule)
            val timezone = normalizeTimezone(command.timezone)
            val prompt = command.prompt.cleanRequired("prompt")

            requireUniqueName(jobs, name)
            require(!command.enabled || jobs.count { it.enabled } < ACTIVE_JOB_LIMIT) {
                "A channel may have at most $ACTIVE_JOB_LIMIT active scheduled jobs."
            }

            val job =
                ScheduledJob(
                    id = store.allocateId(channelId),
                    name = name,
                    description = description,
                    schedule = schedule,
                    timezone = timezone,
                    prompt = prompt,
                    enabled = command.enabled,
                )

            jobs += job
            store.save(channelId, jobs)
            job
        }

    suspend fun update(
        channelId: String,
        jobId: Int,
        command: UpdateScheduledJob,
    ): ScheduledJob =
        store.withChannelLock(channelId) {
            require(command.hasChanges()) { "At least one job field must be changed." }

            val jobs = store.load(channelId).toMutableList()
            val index = jobs.indexOfFirst { it.id == jobId }
            require(index >= 0) { "Scheduled job not found: $jobId" }
            val current = jobs[index]
            val updated =
                current.copy(
                    name = command.name?.cleanRequired("name") ?: current.name,
                    description = if (command.updateDescription) command.description.cleanOptional() else current.description,
                    schedule = command.schedule?.let(::normalizeSchedule) ?: current.schedule,
                    timezone = command.timezone?.let(::normalizeTimezone) ?: current.timezone,
                    prompt = command.prompt?.cleanRequired("prompt") ?: current.prompt,
                    enabled = command.enabled ?: current.enabled,
                )

            requireUniqueName(jobs, updated.name, excludingId = current.id)
            jobs[index] = updated
            requireActiveLimit(jobs)

            store.save(channelId, jobs)
            updated
        }

    suspend fun delete(
        channelId: String,
        jobId: Int,
    ): ScheduledJob =
        store.withChannelLock(channelId) {
            val jobs = store.load(channelId).toMutableList()
            val removed = jobs.find { it.id == jobId } ?: throw IllegalArgumentException("Scheduled job not found: $jobId")

            jobs.remove(removed)
            store.save(channelId, jobs)
            removed
        }

    suspend fun claimDueRuns(now: Instant): List<ScheduledJobRun> {
        val claimed = mutableListOf<ScheduledJobRun>()

        store.channelIds().forEach { channelId ->
            store.withChannelLock(channelId) {
                val jobs = store.load(channelId).toMutableList()
                var changed = false

                jobs.forEachIndexed { index, job ->
                    if (!job.isDue(now)) return@forEachIndexed

                    val claimedJob = job.copy(lastRunAt = now.toEpochMilli())
                    jobs[index] = claimedJob
                    claimed += ScheduledJobRun(channelId, claimedJob)
                    changed = true
                }

                if (changed) store.save(channelId, jobs)
            }
        }

        return claimed
    }

    private fun requireUniqueName(
        jobs: List<ScheduledJob>,
        name: String,
        excludingId: Int? = null,
    ) {
        require(jobs.none { it.id != excludingId && it.name.equals(name, ignoreCase = true) }) {
            "A scheduled job named '$name' already exists in this channel."
        }
    }

    private fun requireActiveLimit(jobs: List<ScheduledJob>) {
        require(jobs.count { it.enabled } <= ACTIVE_JOB_LIMIT) {
            "A channel may have at most $ACTIVE_JOB_LIMIT active scheduled jobs."
        }
    }
}

fun normalizeSchedule(value: String): String {
    val normalized = value.trim().split(Regex("\\s+")).joinToString(" ")
    require(normalized.split(' ').size == 5) { "schedule must use five-field cron: minute hour day-of-month month day-of-week" }
    runCatching { CronExpression.parse("0 $normalized") }
        .getOrElse { throw IllegalArgumentException("Invalid schedule: ${it.message}") }
    return normalized
}

fun normalizeTimezone(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "timezone is required" }
    return runCatching { ZoneId.of(normalized).id }
        .getOrElse { throw IllegalArgumentException("Invalid timezone: $normalized") }
}

internal fun ScheduledJob.isDue(now: Instant): Boolean {
    if (!enabled) return false

    val currentMinute = now.atZone(ZoneId.of(timezone)).truncatedTo(ChronoUnit.MINUTES)
    val scheduledAt = cron().next(currentMinute.minusMinutes(1))
    if (scheduledAt != currentMinute) return false

    val scheduledAtMillis = currentMinute.toInstant().toEpochMilli()
    val previousRunAt = lastRunAt ?: return true
    val previousRunMinute = Instant.ofEpochMilli(previousRunAt).truncatedTo(ChronoUnit.MINUTES)
    val currentRunMinute = now.truncatedTo(ChronoUnit.MINUTES)
    return previousRunAt < scheduledAtMillis &&
        currentRunMinute.toEpochMilli() - previousRunMinute.toEpochMilli() >= JOB_COOLDOWN_MILLIS
}

private fun ScheduledJob.cron(): CronExpression = CronExpression.parse("0 $schedule")

private fun String.cleanRequired(field: String): String = trim().also { require(it.isNotEmpty()) { "$field is required" } }

private fun String?.cleanOptional(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
