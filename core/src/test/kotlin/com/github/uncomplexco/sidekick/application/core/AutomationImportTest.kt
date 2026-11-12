package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.testing.AutomationFixture
import com.github.uncomplexco.sidekick.testing.automationFixture
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private const val LEGACY_AUTOMATION =
    """{"id":7,"name":"Report","description":"daily","schedule":"0 9 * * *","timezone":"UTC","prompt":"Summarize alerts","enabled":true,"last_run_at":1786957200000}"""

class AutomationImportTest {
    @TempDir lateinit var dir: Path
    private val now = Instant.parse("2026-11-11T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `explicit import maps legacy fields and preserves the source files`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        fixture.importer.importProject("C123")

        // Assert
        val stored = AutomationFixture(fixture.config).repository.load("C123").single()
        assertEquals(7, stored.uid)
        assertEquals("C123", stored.projectUid)
        assertEquals("Report", stored.name)
        assertEquals("daily", stored.description)
        assertEquals("Summarize alerts", stored.instructions)
        assertEquals(scheduledTrigger("0 9 * * *", "UTC"), stored.trigger)
        assertEquals(Instant.ofEpochMilli(1786957200000), stored.lastRunAt)
        assertEquals(now, stored.createdAt)
        assertEquals(now, stored.updatedAt)
        assertEquals(
            LEGACY_AUTOMATION,
            Files.readString(dir.resolve("state/slack/channels/C123/jobs.jsonl")),
        )
        assertEquals(
            "12\n",
            Files.readString(dir.resolve("state/slack/channels/C123/jobs-sequence")),
        )
    }

    @Test
    fun `repeated import leaves database identities and timestamps unchanged`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)
        fixture.importer.importProject("C123")
        val original = fixture.repository.load("C123")
        fixture.writeLegacy("C123", "invalid json", 99)

        // Act
        AutomationFixture(fixture.config, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC))
            .importer
            .importProject("C123")

        // Assert
        assertEquals(original, AutomationFixture(fixture.config).repository.load("C123"))
    }

    @Test
    fun `first creation imports all legacy automations and continues the allocation sequence`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val created =
            fixture.service.create(
                "C123",
                CreateAutomation(
                    "New",
                    null,
                    "New instructions",
                    scheduledTrigger("0 * * * *", "UTC"),
                ),
            )

        // Assert
        val stored = AutomationFixture(fixture.config).repository.load("C123")
        assertEquals(13, created.uid)
        assertEquals(listOf(7, 13), stored.map { it.uid })
        assertEquals(created, stored.last())
        assertFalse(
            Files.readString(dir.resolve("state/slack/channels/C123/jobs.jsonl"))
                .contains("New instructions")
        )
    }

    @Test
    fun `first update imports and changes only the addressed automation in SQLite`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val updated =
            fixture.service.update(
                "C123",
                7,
                UpdateAutomation(instructions = "Updated instructions"),
            )

        // Assert
        assertEquals("Updated instructions", updated.instructions)
        assertEquals(updated, AutomationFixture(fixture.config).repository.load("C123").single())
        assertEquals(
            LEGACY_AUTOMATION,
            Files.readString(dir.resolve("state/slack/channels/C123/jobs.jsonl")),
        )
    }

    @Test
    fun `deleting the last legacy automation cannot resurrect it on reads or scheduler scans`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val removed = fixture.service.delete("C123", 7)

        // Assert
        val reloaded = AutomationFixture(fixture.config)
        assertEquals(7, removed.uid)
        assertTrue(reloaded.repository.isImported("C123"))
        assertTrue(reloaded.service.list("C123").isEmpty())
        assertTrue(reloaded.service.claimDueRuns(now).isEmpty())
        assertTrue(reloaded.repository.load("C123").isEmpty())
    }

    @Test
    fun `scheduler imports legacy automations before evaluating and claiming due runs`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val claimed = fixture.service.claimDueRuns(now)

        // Assert
        val persisted = AutomationFixture(fixture.config).repository.load("C123").single()
        assertEquals(persisted, claimed.single().automation)
        assertEquals(now, persisted.lastRunAt)
        assertTrue(fixture.service.claimDueRuns(now).isEmpty())
    }

    @Test
    fun `scheduler imports a legacy automation even when it is not due`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val claimed = fixture.service.claimDueRuns(now.plusSeconds(60))

        // Assert
        assertTrue(claimed.isEmpty())
        assertEquals(7, AutomationFixture(fixture.config).repository.load("C123").single().uid)
    }

    @Test
    fun `global identities differ while legacy local numbers stay project scoped`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)
        fixture.writeLegacy("C456", LEGACY_AUTOMATION, 12)

        // Act
        fixture.service.claimDueRuns(now)

        // Assert
        val repository = AutomationFixture(fixture.config).repository
        val first = repository.load("C123").single()
        val second = repository.load("C456").single()
        assertEquals(7, first.uid)
        assertEquals(7, second.uid)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `failed import rolls back its marker and rows`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", "$LEGACY_AUTOMATION\n$LEGACY_AUTOMATION", 12)

        // Act
        assertThrows<Exception> { fixture.importer.importProject("C123") }

        // Assert
        val repository = AutomationFixture(fixture.config).repository
        assertFalse(repository.isImported("C123"))
        assertTrue(repository.load("C123").isEmpty())
    }

    @Test
    fun `corrected source can be imported after a failed import`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", "$LEGACY_AUTOMATION\n$LEGACY_AUTOMATION", 12)
        assertThrows<Exception> { fixture.importer.importProject("C123") }
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        fixture.importer.importProject("C123")

        // Assert
        val repository = AutomationFixture(fixture.config).repository
        assertTrue(repository.isImported("C123"))
        assertEquals(7, repository.load("C123").single().uid)
    }

    @Test
    fun `listing imports the legacy source and returns persisted identities`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", LEGACY_AUTOMATION, 12)

        // Act
        val listed = fixture.service.list("C123")

        // Assert
        assertEquals(AutomationFixture(fixture.config).repository.load("C123"), listed)
        assertEquals(7, listed.single().uid)
    }

    @Test
    fun `scheduler imports sequence-only projects without reusing deleted local numbers`() {
        // Arrange
        val fixture = automationFixture(dir, clock)
        fixture.writeLegacy("C123", "", 12)
        Files.delete(dir.resolve("state/slack/channels/C123/jobs.jsonl"))

        // Act
        val claimed = fixture.service.claimDueRuns(now)

        // Assert
        val reloaded = AutomationFixture(fixture.config)
        assertTrue(claimed.isEmpty())
        assertTrue(reloaded.repository.isImported("C123"))
        assertEquals(
            13,
            reloaded.service
                .create(
                    "C123",
                    CreateAutomation(
                        "new",
                        null,
                        "Instructions",
                        scheduledTrigger("0 9 * * *", "UTC"),
                    ),
                )
                .uid,
        )
    }
}
