package com.github.uncomplexco.sidekick.ports.scheduling

import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobRun

fun interface ScheduledJobDispatcher {
    suspend fun dispatch(run: ScheduledJobRun)
}
