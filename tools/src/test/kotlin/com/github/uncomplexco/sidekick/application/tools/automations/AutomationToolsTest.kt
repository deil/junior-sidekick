package com.github.uncomplexco.sidekick.application.tools.automations

import com.github.uncomplexco.sidekick.application.core.scheduledTrigger
import com.github.uncomplexco.sidekick.testing.AutomationFixture
import com.github.uncomplexco.sidekick.testing.automationFixture
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AutomationToolsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `registers namespaced automation tool names`() {
        // Arrange
        val tools = AutomationTools("C123", automationFixture(dir).service)

        // Act
        val names = tools.asTools().map { it.name }.toSet()

        // Assert
        assertEquals(
            setOf(
                "automations__create",
                "automations__list",
                "automations__update",
                "automations__delete",
            ),
            names,
        )
    }

    @Test
    fun `creates automation with instructions trigger and project local uid`() {
        // Arrange
        val fixture = automationFixture(dir)
        val tools = AutomationTools("C123", fixture.service)

        // Act
        val result =
            tools.createAutomation(
                "Daily",
                null,
                "Report",
                scheduledTrigger("0 9 * * *", "Europe/London"),
            )

        // Assert
        val stored = AutomationFixture(fixture.config).repository.load(fixture.projectId).single()
        assertEquals(1, result.automation.uid)
        assertEquals("Report", stored.instructions)
        assertEquals(result.automation.id, stored.id)
        assertEquals(scheduledTrigger("0 9 * * *", "Europe/London"), stored.trigger)
    }

    @Test
    fun `pauses automation by local uid`() {
        // Arrange
        val fixture = automationFixture(dir)
        val tools = AutomationTools("C123", fixture.service)
        val created =
            tools
                .createAutomation("Daily", "old", "Report", scheduledTrigger("0 9 * * *", "UTC"))
                .automation

        // Act
        val result = tools.updateAutomation(created.uid, description = "", enabled = false)

        // Assert
        val stored = AutomationFixture(fixture.config).repository.load(fixture.projectId).single()
        assertFalse(result.automation.enabled)
        assertFalse(stored.enabled)
        assertEquals(null, stored.description)
        assertEquals(created.id, stored.id)
    }

    @Test
    fun `deletes automation by local uid without changing another project`() {
        // Arrange
        val fixture = automationFixture(dir)
        val tools = AutomationTools("C123", fixture.service)
        val created =
            tools
                .createAutomation("Daily", null, "Report", scheduledTrigger("0 9 * * *", "UTC"))
                .automation
        val other =
            AutomationTools("C456", fixture.service)
                .createAutomation("Daily", null, "Report", scheduledTrigger("0 9 * * *", "UTC"))
                .automation

        // Act
        val deleted = tools.deleteAutomation(created.uid)

        // Assert
        val repository = AutomationFixture(fixture.config).repository
        assertEquals(created.uid, deleted.uid)
        assertEquals(emptyList(), repository.load(fixture.projectId))
        assertEquals(other.id, repository.load(fixture.otherProjectId).single().id)
    }
}
