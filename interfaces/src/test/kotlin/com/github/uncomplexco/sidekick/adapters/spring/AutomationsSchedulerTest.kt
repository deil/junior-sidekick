package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.application.core.AutomationRun
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.ports.AutomationDispatcher
import com.github.uncomplexco.sidekick.testing.AutomationFixture
import com.github.uncomplexco.sidekick.testing.automationFixture
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.StaticListableBeanFactory

class AutomationsSchedulerTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `imports claims and dispatches a legacy automation due in current minute`() {
        // Arrange
        val fixture = automationFixture(dir)
        fixture.writeLegacy(
            "C123",
            """{"id":7,"name":"hourly","schedule":"* * * * *","timezone":"UTC","prompt":"report"}""",
            12,
        )
        val dispatched = mutableListOf<AutomationRun>()
        val dispatcher = AutomationDispatcher { dispatched.add(it) }
        val provider =
            StaticListableBeanFactory(mapOf("dispatcher" to dispatcher))
                .getBeanProvider(AutomationDispatcher::class.java)
        val scheduler =
            AutomationsScheduler(
                fixture.service,
                provider,
                SidekickCoroutineScope(CoroutineScope(Dispatchers.Unconfined)),
            )

        // Act
        scheduler.tick()

        // Assert
        val stored = AutomationFixture(fixture.config).repository.load(fixture.projectId).single()
        assertEquals(fixture.projectId, dispatched.single().project.id)
        assertEquals(stored, dispatched.single().automation)
        assertEquals(7, stored.uid)
        assertNotNull(stored.lastRunAt)
    }
}
