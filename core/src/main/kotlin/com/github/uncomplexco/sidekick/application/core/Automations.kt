package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.AutomationsRepository
import com.github.uncomplexco.sidekick.ports.LegacyAutomationsReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component

const val ACTIVE_AUTOMATION_LIMIT = 5
const val AUTOMATION_COOLDOWN_MILLIS = 60 * 60 * 1000L

data class Automation(
    val id: Long,
    val projectUid: String,
    val uid: Int,
    val name: String,
    val description: String?,
    val instructions: String,
    val trigger: JsonObject,
    val enabled: Boolean,
    val lastRunAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AutomationRun(val projectUid: String, val automation: Automation)

data class CreateAutomation(
    val name: String,
    val description: String?,
    val instructions: String,
    val trigger: JsonObject,
    val enabled: Boolean = true,
)

data class UpdateAutomation(
    val name: String? = null,
    val description: String? = null,
    val updateDescription: Boolean = false,
    val instructions: String? = null,
    val trigger: JsonObject? = null,
    val enabled: Boolean? = null,
) {
    fun hasChanges(): Boolean =
        name != null ||
            updateDescription ||
            instructions != null ||
            trigger != null ||
            enabled != null
}

@Component
class AutomationImporter(
    private val automations: AutomationsRepository,
    private val legacy: LegacyAutomationsReader,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun importProject(projectUid: String) {
        val imported =
            automations.inProjectTransaction(projectUid) {
                if (automations.isImported(projectUid)) return@inProjectTransaction false

                val snapshot = legacy.load(projectUid)
                val now = clock.instant()
                automations.initialize(projectUid, snapshot.lastAllocatedUid)
                snapshot.automations.forEach {
                    automations.insert(projectUid, it.uid, it.command, now, it.lastRunAt)
                }
                true
            }
        if (imported)
            Loggers.AUTOMATIONS.info("Imported automations into SQLite for project {}", projectUid)
    }
}

@Component
class AutomationService(
    private val automations: AutomationsRepository,
    private val legacy: LegacyAutomationsReader,
    private val importer: AutomationImporter,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(projectUid: String): List<Automation> {
        importer.importProject(projectUid)
        return automations.load(projectUid).sortedBy { it.name.lowercase() }
    }

    fun create(projectUid: String, command: CreateAutomation): Automation =
        automations.inProjectTransaction(projectUid) {
            importer.importProject(projectUid)
            val existing = automations.load(projectUid)
            val normalized =
                command.copy(
                    name = command.name.cleanRequired("name"),
                    description = command.description.cleanOptional(),
                    instructions = command.instructions.cleanRequired("instructions"),
                    trigger = normalizeTrigger(command.trigger),
                )
            requireUniqueName(existing, normalized.name)
            require(
                !normalized.enabled || existing.count { it.enabled } < ACTIVE_AUTOMATION_LIMIT
            ) {
                "A project may have at most $ACTIVE_AUTOMATION_LIMIT active automations."
            }
            val created =
                automations.insert(
                    projectUid,
                    automations.allocateUid(projectUid),
                    normalized,
                    clock.instant(),
                )
            Loggers.AUTOMATIONS.info(
                "Created automation: project={} uid={}",
                projectUid,
                created.uid,
            )
            created
        }

    fun update(projectUid: String, uid: Int, command: UpdateAutomation): Automation =
        automations.inProjectTransaction(projectUid) {
            importer.importProject(projectUid)
            require(command.hasChanges()) { "At least one automation field must be changed." }
            val existing = automations.load(projectUid)
            val current =
                existing.find { it.uid == uid }
                    ?: throw IllegalArgumentException("Automation not found: $uid")
            val updated =
                current.copy(
                    name = command.name?.cleanRequired("name") ?: current.name,
                    description =
                        if (command.updateDescription) command.description.cleanOptional()
                        else current.description,
                    instructions =
                        command.instructions?.cleanRequired("instructions") ?: current.instructions,
                    trigger = command.trigger?.let(::normalizeTrigger) ?: current.trigger,
                    enabled = command.enabled ?: current.enabled,
                    updatedAt = clock.instant(),
                )
            requireUniqueName(existing.filter { it.uid != uid }, updated.name)
            require(
                existing.count { it.enabled && it.uid != uid } + (if (updated.enabled) 1 else 0) <=
                    ACTIVE_AUTOMATION_LIMIT
            ) {
                "A project may have at most $ACTIVE_AUTOMATION_LIMIT active automations."
            }
            automations.update(updated)
            Loggers.AUTOMATIONS.info("Updated automation: project={} uid={}", projectUid, uid)
            updated
        }

    fun delete(projectUid: String, uid: Int): Automation =
        automations.inProjectTransaction(projectUid) {
            importer.importProject(projectUid)
            val removed =
                automations.load(projectUid).find { it.uid == uid }
                    ?: throw IllegalArgumentException("Automation not found: $uid")
            automations.delete(removed.id)
            Loggers.AUTOMATIONS.info("Deleted automation: project={} uid={}", projectUid, uid)
            removed
        }

    fun claimDueRuns(now: Instant): List<AutomationRun> =
        (automations.projectUids() + legacy.projectUids()).distinct().sorted().flatMap { projectUid
            ->
            automations.inProjectTransaction(projectUid) {
                importer.importProject(projectUid)
                automations
                    .load(projectUid)
                    .filter { it.isDue(now) }
                    .map {
                        val claimed = it.copy(lastRunAt = now, updatedAt = now)
                        automations.update(claimed)
                        Loggers.AUTOMATIONS.info(
                            "Claimed automation: project={} uid={}",
                            projectUid,
                            it.uid,
                        )
                        AutomationRun(projectUid, claimed)
                    }
            }
        }

    private fun requireUniqueName(existing: List<Automation>, name: String) {
        require(existing.none { it.name.lowercase() == name.lowercase() }) {
            "An automation named '$name' already exists in this project."
        }
    }
}

fun scheduledTrigger(schedule: String, timezone: String): JsonObject = buildJsonObject {
    put("type", "scheduled")
    put("schedule", schedule)
    put("timezone", timezone)
}

fun normalizeTrigger(trigger: JsonObject): JsonObject {
    val type = trigger.stringField("type")
    require(type.isNotBlank()) { "trigger.type is required" }
    if (type != "scheduled") return trigger
    val schedule = trigger.stringField("schedule").trim().split(Regex("\\s+")).joinToString(" ")
    require(schedule.split(' ').size == 5) {
        "schedule must use five-field cron: minute hour day-of-month month day-of-week"
    }
    CronExpression.parse("0 $schedule")
    val timezone = ZoneId.of(trigger.stringField("timezone").trim()).id
    return JsonObject(
        trigger +
            mapOf("schedule" to JsonPrimitive(schedule), "timezone" to JsonPrimitive(timezone))
    )
}

private fun JsonObject.stringField(name: String): String {
    val value = get(name) as? JsonPrimitive
    require(value?.isString == true) { "trigger.$name must be a string" }
    return value.content
}

internal fun Automation.isDue(now: Instant): Boolean {
    if (!enabled || trigger["type"]?.jsonPrimitive?.content != "scheduled") return false
    val currentMinute =
        now.atZone(ZoneId.of(trigger.stringField("timezone"))).truncatedTo(ChronoUnit.MINUTES)
    val scheduledAt =
        CronExpression.parse("0 ${trigger.stringField("schedule")}")
            .next(currentMinute.minusMinutes(1))
    if (scheduledAt != currentMinute) return false
    val previousRunAt = lastRunAt ?: return true
    return previousRunAt < currentMinute.toInstant() &&
        now.truncatedTo(ChronoUnit.MINUTES).toEpochMilli() -
            previousRunAt.truncatedTo(ChronoUnit.MINUTES).toEpochMilli() >=
            AUTOMATION_COOLDOWN_MILLIS
}

private fun String.cleanRequired(field: String): String =
    trim().also { require(it.isNotEmpty()) { "$field is required" } }

private fun String?.cleanOptional(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
