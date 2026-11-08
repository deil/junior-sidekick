package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Project
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate

@Repository
class SqliteProjectsRepository(
    private val sidekickJdbcClient: JdbcClient,
    private val sidekickTransactions: TransactionTemplate,
) : ProjectsRepository {
    override fun isEmpty(): Boolean =
        sidekickJdbcClient
            .sql("SELECT NOT EXISTS (SELECT 1 FROM projects)")
            .query(Boolean::class.java)
            .single()

    override fun findByUid(uid: String): Project? =
        sidekickJdbcClient
            .sql(
                "SELECT id, uid, adapter, type, name, external_ref_id, created_at FROM projects WHERE uid = :uid"
            )
            .param("uid", uid)
            .query { row, _ ->
                Project(
                    uid = row.getString("uid"),
                    adapter = ChatPlatform.valueOf(row.getString("adapter").uppercase()),
                    type = ProjectType.valueOf(row.getString("type").uppercase()),
                    name = row.getString("name"),
                    externalRefId = row.getString("external_ref_id"),
                    createdAt = Instant.parse(row.getString("created_at")),
                    id = row.getLong("id"),
                )
            }
            .optional()
            .orElse(null)

    override fun addIfAbsent(project: Project): Boolean =
        sidekickJdbcClient
            .sql(
                """
                INSERT INTO projects (uid, adapter, type, name, external_ref_id, created_at)
                VALUES (:uid, :adapter, :type, :name, :external_ref_id, :created_at)
                ON CONFLICT DO NOTHING
                """
                    .trimIndent()
            )
            .param("uid", project.uid)
            .param("adapter", project.adapter.name.lowercase())
            .param("type", project.type.name.lowercase())
            .param("name", project.name)
            .param("external_ref_id", project.externalRefId)
            .param("created_at", project.createdAt.toString())
            .update() == 1

    override fun seedIfEmpty(projects: List<Project>): Boolean =
        sidekickTransactions.execute {
            if (!isEmpty()) return@execute false

            projects.forEach { project ->
                check(addIfAbsent(project)) { "Conflicting project in seed: ${project.uid}" }
            }

            true
        } == true
}
