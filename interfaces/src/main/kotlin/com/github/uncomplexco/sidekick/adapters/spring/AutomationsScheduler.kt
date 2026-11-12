package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.application.core.AutomationService
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.AutomationDispatcher
import java.time.Instant
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AutomationsScheduler(
    private val automations: AutomationService,
    private val dispatcherProvider: ObjectProvider<AutomationDispatcher>,
    private val scope: SidekickCoroutineScope,
) {
    @Scheduled(cron = "0 * * * * *")
    fun tick() {
        val dispatcher = dispatcherProvider.ifAvailable ?: return
        val claimed =
            try {
                automations.claimDueRuns(Instant.now())
            } catch (error: Exception) {
                Loggers.AUTOMATIONS.error("Failed to claim automations", error)
                return
            }

        claimed.forEach { run ->
            scope.launch {
                runCatching { dispatcher.dispatch(run) }
                    .onFailure { error ->
                        Loggers.AUTOMATIONS.error(
                            "Automation failed: project={} uid={} name={}",
                            run.projectUid,
                            run.automation.uid,
                            run.automation.name,
                            error,
                        )
                    }
            }
        }
    }
}
