package com.github.uncomplexco.sidekick.application.scheduling

fun interface ScheduledJobDispatcher {
    suspend fun dispatch(run: ScheduledJobRun)
}
