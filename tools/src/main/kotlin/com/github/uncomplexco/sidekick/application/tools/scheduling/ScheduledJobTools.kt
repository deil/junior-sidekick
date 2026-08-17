package com.github.uncomplexco.sidekick.application.tools.scheduling

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.scheduling.ACTIVE_JOB_LIMIT
import com.github.uncomplexco.sidekick.application.scheduling.CreateScheduledJob
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobService
import com.github.uncomplexco.sidekick.application.scheduling.UpdateScheduledJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class ScheduledJobTools(
    private val channelId: String,
    private val jobs: ScheduledJobService,
) : ToolSet {
    @Tool("scheduled_jobs__create")
    @LLMDescription(
        "Create a recurring scheduled job. Convert the user's requested schedule to five-field cron. Jobs run at minute precision and no more than hourly.",
    )
    fun createScheduledJob(
        @LLMDescription("Required unique human-readable job name")
        name: String,
        @LLMDescription("Optional job description")
        description: String? = null,
        @LLMDescription("Required five-field cron: minute hour day-of-month month day-of-week")
        schedule: String,
        @LLMDescription("Required IANA timezone")
        timezone: String,
        @LLMDescription("Required instruction executed on every scheduled run")
        prompt: String,
        @LLMDescription("Whether the job is active. Defaults to true")
        enabled: Boolean = true,
    ): ScheduledJobResult =
        asToolCall {
            validate(name.isNotBlank()) { "name is required" }
            validate(schedule.isNotBlank()) { "schedule is required" }
            validate(timezone.isNotBlank()) { "timezone is required" }
            validate(prompt.isNotBlank()) { "prompt is required" }

            val job =
                runBlocking {
                    jobs.create(
                        channelId,
                        CreateScheduledJob(name, description, schedule, timezone, prompt, enabled),
                    )
                }
            ScheduledJobResult(job.toView())
        }

    @Tool("scheduled_jobs__list")
    @LLMDescription("List all scheduled jobs.")
    fun listScheduledJobs(): ScheduledJobListResult {
        val listed = jobs.list(channelId)
        return ScheduledJobListResult(
            jobs = listed.map(ScheduledJob::toView),
            active_count = listed.count { it.enabled },
            active_limit = ACTIVE_JOB_LIMIT,
        )
    }

    @Tool("scheduled_jobs__update")
    @LLMDescription(
        "Update a given scheduled job. Supply only changed fields. Set enabled=false to pause or enabled=true to resume. Use an empty description to clear it.",
    )
    fun updateScheduledJob(
        @LLMDescription("ID of the job to update")
        job_id: Int,
        @LLMDescription("New unique human-readable name")
        name: String? = null,
        @LLMDescription("New description. Empty text clears the description")
        description: String? = null,
        @LLMDescription("New five-field cron: minute hour day-of-month month day-of-week")
        schedule: String? = null,
        @LLMDescription("New IANA timezone")
        timezone: String? = null,
        @LLMDescription("New instruction executed on every scheduled run")
        prompt: String? = null,
        @LLMDescription("Set false to pause or true to resume")
        enabled: Boolean? = null,
    ): ScheduledJobResult =
        asToolCall {
            val job =
                runBlocking {
                    jobs.update(
                        channelId,
                        job_id,
                        UpdateScheduledJob(
                            name = name,
                            description = description,
                            updateDescription = description != null,
                            schedule = schedule,
                            timezone = timezone,
                            prompt = prompt,
                            enabled = enabled,
                        ),
                    )
                }
            ScheduledJobResult(job.toView())
        }

    @Tool("scheduled_jobs__delete")
    @LLMDescription("Permanently delete a scheduled job.")
    fun deleteScheduledJob(
        @LLMDescription("ID of the job to delete")
        job_id: Int,
    ): DeletedScheduledJobResult =
        asToolCall {
            val deleted = runBlocking { jobs.delete(channelId, job_id) }
            DeletedScheduledJobResult(deleted.id, deleted.name)
        }

    private fun <T> asToolCall(block: () -> T): T =
        try {
            block()
        } catch (error: ToolException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw ToolException.ValidationFailure(error.message ?: "Invalid scheduled job request")
        }
}

@Serializable
data class ScheduledJobResult(
    val job: ScheduledJobView,
)

@Serializable
data class ScheduledJobListResult(
    val jobs: List<ScheduledJobView>,
    val active_count: Int,
    val active_limit: Int,
)

@Serializable
data class DeletedScheduledJobResult(
    val id: Int,
    val name: String,
)

@Serializable
data class ScheduledJobView(
    val id: Int,
    val name: String,
    val description: String?,
    val schedule: String,
    val timezone: String,
    val prompt: String,
    val enabled: Boolean,
    val last_run_at: Long?,
)

private fun ScheduledJob.toView(): ScheduledJobView =
    ScheduledJobView(id, name, description, schedule, timezone, prompt, enabled, lastRunAt)
