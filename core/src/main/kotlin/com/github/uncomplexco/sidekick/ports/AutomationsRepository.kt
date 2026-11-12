package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.Automation
import com.github.uncomplexco.sidekick.application.core.CreateAutomation
import java.time.Instant

interface AutomationsRepository {
    fun projectIds(): List<Long>

    fun isImported(projectId: Long): Boolean

    fun initialize(projectId: Long, lastAllocatedUid: Int)

    fun allocateUid(projectId: Long): Int

    fun load(projectId: Long): List<Automation>

    fun insert(
        projectId: Long,
        uid: Int,
        command: CreateAutomation,
        now: Instant,
        lastRunAt: Instant? = null,
    ): Automation

    fun update(automation: Automation)

    fun delete(id: Long)

    fun <T : Any> inProjectTransaction(projectId: Long, operation: () -> T): T
}
