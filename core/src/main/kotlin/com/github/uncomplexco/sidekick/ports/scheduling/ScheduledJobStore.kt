package com.github.uncomplexco.sidekick.ports.scheduling

import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob

interface ScheduledJobStore {
    fun channelIds(): List<String>

    fun allocateId(channelId: String): Int

    fun load(channelId: String): List<ScheduledJob>

    fun save(
        channelId: String,
        jobs: List<ScheduledJob>,
    )

    suspend fun <T> withChannelLock(
        channelId: String,
        block: suspend () -> T,
    ): T
}
