package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.testing.AutomationFixture
import com.github.uncomplexco.sidekick.testing.automationFixture
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class AutomationServiceTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `creation normalizes the instructions and scheduled trigger without dropping extra fields`() {
        // Arrange
        val fixture = automationFixture(dir)
        val trigger =
            JsonObject(
                scheduledTrigger(" 0   9 * * 1-5 ", "Europe/London") +
                    ("extra" to JsonPrimitive("kept"))
            )

        // Act
        val created =
            fixture.service.create(
                "C123",
                CreateAutomation("  Daily  ", " description ", " Report ", trigger),
            )

        // Assert
        assertEquals("Daily", created.name)
        assertEquals("description", created.description)
        assertEquals("Report", created.instructions)
        assertEquals(
            JsonObject(
                scheduledTrigger("0 9 * * 1-5", "Europe/London") +
                    ("extra" to JsonPrimitive("kept"))
            ),
            created.trigger,
        )
        assertEquals(
            created,
            AutomationFixture(fixture.config).repository.load(fixture.projectId).single(),
        )
    }

    @Test
    fun `sixth active automation is rejected without changing state or consuming a local number`() {
        // Arrange
        val fixture = automationFixture(dir)
        repeat(5) { fixture.service.create("C123", command("automation $it")) }
        val original = fixture.repository.load(fixture.projectId)

        // Act
        assertThrows<IllegalArgumentException> { fixture.service.create("C123", command("sixth")) }

        // Assert
        assertEquals(original, AutomationFixture(fixture.config).repository.load(fixture.projectId))
        assertEquals(6, fixture.service.create("C123", command("paused").copy(enabled = false)).uid)
    }

    @Test
    fun `duplicate names are rejected case insensitively including paused automations`() {
        // Arrange
        val fixture = automationFixture(dir)
        val created = fixture.service.create("C123", command("Daily").copy(enabled = false))

        // Act
        assertThrows<IllegalArgumentException> { fixture.service.create("C123", command("DAILY")) }

        // Assert
        assertEquals(
            listOf(created),
            AutomationFixture(fixture.config).repository.load(fixture.projectId),
        )
    }

    @Test
    fun `update preserves identity creation and last claim while replacing the trigger`() {
        // Arrange
        val fixture = automationFixture(dir)
        val created = fixture.service.create("C123", command("daily"))
        val claimed =
            fixture.service.claimDueRuns(Instant.parse("2026-11-11T09:00:00Z")).single().automation
        val replacement =
            JsonObject(
                mapOf("type" to JsonPrimitive("external"), "source" to JsonPrimitive("test"))
            )

        // Act
        val updated =
            fixture.service.update(
                "C123",
                created.uid,
                UpdateAutomation(trigger = replacement, enabled = false),
            )

        // Assert
        assertEquals(created.id, updated.id)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(claimed.lastRunAt, updated.lastRunAt)
        assertEquals(replacement, updated.trigger)
        assertFalse(updated.enabled)
        assertEquals(
            updated,
            AutomationFixture(fixture.config).repository.load(fixture.projectId).single(),
        )
    }

    @Test
    fun `unknown trigger types are persisted but never dispatched by the scheduler`() {
        // Arrange
        val fixture = automationFixture(dir)
        val created =
            fixture.service.create(
                "C123",
                command("external")
                    .copy(trigger = JsonObject(mapOf("type" to JsonPrimitive("external")))),
            )

        // Act
        val runs = fixture.service.claimDueRuns(Instant.parse("2026-11-11T09:00:00Z"))

        // Assert
        assertTrue(runs.isEmpty())
        assertEquals(
            created,
            AutomationFixture(fixture.config).repository.load(fixture.projectId).single(),
        )
    }

    @Test
    fun `scheduler keeps minute precision cooldown and does not catch up missed occurrences`() {
        // Arrange
        val fixture = automationFixture(dir)
        fixture.service.create(
            "C123",
            command("hourly").copy(trigger = scheduledTrigger("0 * * * *", "UTC")),
        )

        // Act
        val first = fixture.service.claimDueRuns(Instant.parse("2026-11-11T09:00:50Z"))
        val duplicate = fixture.service.claimDueRuns(Instant.parse("2026-11-11T09:00:59Z"))
        val missed = fixture.service.claimDueRuns(Instant.parse("2026-11-11T10:01:00Z"))
        val next = fixture.service.claimDueRuns(Instant.parse("2026-11-11T11:00:01Z"))

        // Assert
        assertEquals(1, first.size)
        assertTrue(duplicate.isEmpty())
        assertTrue(missed.isEmpty())
        assertEquals(1, next.size)
    }

    @Test
    fun `missing trigger type is rejected without creating an automation`() {
        // Arrange
        val fixture = automationFixture(dir)

        // Act
        assertThrows<IllegalArgumentException> {
            fixture.service.create(
                "C123",
                command("invalid").copy(trigger = JsonObject(emptyMap())),
            )
        }

        // Assert
        assertTrue(AutomationFixture(fixture.config).repository.load(fixture.projectId).isEmpty())
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidTriggers")
    fun `invalid scheduled trigger is rejected without consuming a local number`(
        scenario: String,
        trigger: JsonObject,
    ) {
        // Arrange
        val fixture = automationFixture(dir)

        // Act
        assertThrows<RuntimeException> {
            fixture.service.create("C123", command("invalid").copy(trigger = trigger))
        }

        // Assert
        assertTrue(
            AutomationFixture(fixture.config).repository.load(fixture.projectId).isEmpty(),
            scenario,
        )
        assertEquals(1, fixture.service.create("C123", command("valid")).uid)
    }

    @Test
    fun `resuming a sixth automation is rejected without changing its persisted state`() {
        // Arrange
        val fixture = automationFixture(dir)
        repeat(5) { fixture.service.create("C123", command("automation $it")) }
        val paused = fixture.service.create("C123", command("paused").copy(enabled = false))

        // Act
        assertThrows<IllegalArgumentException> {
            fixture.service.update("C123", paused.uid, UpdateAutomation(enabled = true))
        }

        // Assert
        assertEquals(
            paused,
            AutomationFixture(fixture.config).repository.load(fixture.projectId).last(),
        )
    }

    companion object {
        @JvmStatic
        fun invalidTriggers() =
            listOf(
                Arguments.of("six-field cron", scheduledTrigger("0 0 9 * * *", "UTC")),
                Arguments.of(
                    "invalid cron",
                    scheduledTrigger("no such cron expression here", "UTC"),
                ),
                Arguments.of("invalid timezone", scheduledTrigger("0 9 * * *", "Not/AZone")),
                Arguments.of(
                    "missing schedule",
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("scheduled"),
                            "timezone" to JsonPrimitive("UTC"),
                        )
                    ),
                ),
                Arguments.of("non-string type", JsonObject(mapOf("type" to JsonPrimitive(1)))),
            )
    }

    private fun command(name: String) =
        CreateAutomation(name, null, "Report", scheduledTrigger("0 9 * * *", "UTC"))
}
