package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.LegacyAutomationProject

interface LegacyAutomationsReader {
    fun projectUids(): List<String>

    fun load(projectUid: String): LegacyAutomationProject
}
