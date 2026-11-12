package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.core.Automation
import com.github.uncomplexco.sidekick.application.core.CreateAutomation
import com.github.uncomplexco.sidekick.ports.AutomationsRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class SqliteAutomationsRepository(
    private val sidekickJdbcClient: JdbcClient,
    private val sidekickTransactions: TransactionTemplate,
) : AutomationsRepository {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val mapper = RowMapper { row, _ ->
        Automation(
            id = row.getLong("id"),
            projectUid = row.getString("project_uid"),
            uid = row.getInt("uid"),
            name = row.getString("name"),
            description = row.getString("description"),
            instructions = row.getString("instructions"),
            trigger = Json.parseToJsonElement(row.getString("trigger")).jsonObject,
            enabled = row.getBoolean("is_enabled"),
            lastRunAt = row.getString("last_run_at")?.let(Instant::parse),
            createdAt = Instant.parse(row.getString("created_at")),
            updatedAt = Instant.parse(row.getString("updated_at")),
        )
    }

    override fun projectUids(): List<String> =
        sidekickJdbcClient
            .sql("SELECT project_uid FROM automation_projects ORDER BY project_uid")
            .query { row, _ -> row.getString("project_uid") }
            .list()

    override fun isImported(projectUid: String): Boolean =
        sidekickJdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM automation_projects WHERE project_uid = :project_uid)"
            )
            .param("project_uid", projectUid)
            .query(Boolean::class.java)
            .single()

    override fun initialize(projectUid: String, lastAllocatedUid: Int) {
        sidekickJdbcClient
            .sql(
                """
                INSERT INTO automation_projects (project_uid, last_allocated_uid)
                VALUES (:project_uid, :last_allocated_uid)
                """
                    .trimIndent()
            )
            .param("project_uid", projectUid)
            .param("last_allocated_uid", lastAllocatedUid)
            .update()
    }

    override fun allocateUid(projectUid: String): Int =
        sidekickJdbcClient
            .sql(
                """
                UPDATE automation_projects SET last_allocated_uid = last_allocated_uid + 1
                WHERE project_uid = :project_uid
                RETURNING last_allocated_uid
                """
                    .trimIndent()
            )
            .param("project_uid", projectUid)
            .query(Int::class.java)
            .single()

    override fun load(projectUid: String): List<Automation> =
        sidekickJdbcClient
            .sql("SELECT * FROM automations WHERE project_uid = :project_uid ORDER BY uid")
            .param("project_uid", projectUid)
            .query(mapper)
            .list()

    override fun insert(
        projectUid: String,
        uid: Int,
        command: CreateAutomation,
        now: Instant,
        lastRunAt: Instant?,
    ): Automation =
        sidekickJdbcClient
            .sql(
                """
                INSERT INTO automations (project_uid, uid, name, name_key, description, instructions,
                    trigger, is_enabled, last_run_at, created_at, updated_at)
                VALUES (:project_uid, :uid, :name, :name_key, :description, :instructions,
                    :trigger, :is_enabled, :last_run_at, :created_at, :updated_at)
                RETURNING *
                """
                    .trimIndent()
            )
            .param("project_uid", projectUid)
            .param("uid", uid)
            .param("name", command.name)
            .param("name_key", command.name.lowercase())
            .param("description", command.description)
            .param("instructions", command.instructions)
            .param("trigger", command.trigger.toString())
            .param("is_enabled", command.enabled)
            .param("last_run_at", lastRunAt?.toString())
            .param("created_at", now.toString())
            .param("updated_at", now.toString())
            .query(mapper)
            .single()

    override fun update(automation: Automation) {
        sidekickJdbcClient
            .sql(
                """
                UPDATE automations SET name = :name, name_key = :name_key, description = :description,
                    instructions = :instructions, trigger = :trigger, is_enabled = :is_enabled,
                    last_run_at = :last_run_at, updated_at = :updated_at
                WHERE id = :id
                """
                    .trimIndent()
            )
            .param("id", automation.id)
            .param("name", automation.name)
            .param("name_key", automation.name.lowercase())
            .param("description", automation.description)
            .param("instructions", automation.instructions)
            .param("trigger", automation.trigger.toString())
            .param("is_enabled", automation.enabled)
            .param("last_run_at", automation.lastRunAt?.toString())
            .param("updated_at", automation.updatedAt.toString())
            .update()
    }

    override fun delete(id: Long) {
        sidekickJdbcClient.sql("DELETE FROM automations WHERE id = :id").param("id", id).update()
    }

    override fun <T : Any> inProjectTransaction(projectUid: String, operation: () -> T): T =
        locks
            .computeIfAbsent(projectUid) { ReentrantLock() }
            .withLock {
                requireNotNull(sidekickTransactions.execute { operation() })
            }
}
