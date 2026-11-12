package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.Automation
import com.github.uncomplexco.sidekick.application.core.CreateAutomation
import java.time.Instant

interface AutomationsRepository {
    fun projectUids(): List<String>

    fun isImported(projectUid: String): Boolean

    fun initialize(projectUid: String, lastAllocatedUid: Int)

    fun allocateUid(projectUid: String): Int

    fun load(projectUid: String): List<Automation>

    fun insert(
        projectUid: String,
        uid: Int,
        command: CreateAutomation,
        now: Instant,
        lastRunAt: Instant? = null,
    ): Automation

    fun update(automation: Automation)

    fun delete(id: Long)

    fun <T : Any> inProjectTransaction(projectUid: String, operation: () -> T): T
}
