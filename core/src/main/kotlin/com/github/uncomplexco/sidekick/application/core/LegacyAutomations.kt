package com.github.uncomplexco.sidekick.application.core

import java.time.Instant

data class LegacyAutomationProject(
    val automations: List<LegacyAutomation>,
    val lastAllocatedUid: Int,
)

data class LegacyAutomation(
    val uid: Int,
    val command: CreateAutomation,
    val lastRunAt: Instant?,
)
