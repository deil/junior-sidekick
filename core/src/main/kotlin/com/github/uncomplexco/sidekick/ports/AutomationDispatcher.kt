package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.AutomationRun

fun interface AutomationDispatcher {
    suspend fun dispatch(run: AutomationRun)
}
