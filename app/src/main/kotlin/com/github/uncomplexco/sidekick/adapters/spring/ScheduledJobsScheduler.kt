package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobService
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.scheduling.ScheduledJobDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ScheduledJobsScheduler(
    private val jobs: ScheduledJobService,
    private val dispatcherProvider: ObjectProvider<ScheduledJobDispatcher>,
    private val scope: CoroutineScope,
) {
    @Scheduled(cron = "0 * * * * *")
    fun tick() {
        val dispatcher = dispatcherProvider.ifAvailable ?: return
        val claimed =
            try {
                runBlocking { jobs.claimDueRuns(Instant.now()) }
            } catch (error: Exception) {
                Loggers.SCHEDULED_JOBS.error("Failed to claim scheduled jobs", error)
                return
            }

        claimed.forEach { run ->
            scope.launch {
                runCatching { dispatcher.dispatch(run) }
                    .onFailure { error ->
                        Loggers.SCHEDULED_JOBS.error(
                            "Scheduled job failed: channel={} job_id={} name={}",
                            run.channelId,
                            run.job.id,
                            run.job.name,
                            error,
                        )
                    }
            }
        }
    }
}
