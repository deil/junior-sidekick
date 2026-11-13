package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.chat.ChannelKind
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Project
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import java.time.Instant
import org.springframework.jdbc.core.RowMapper
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

    override fun findByUid(uid: String): Project? = find("uid", uid)

    override fun findById(id: Long): Project? = find("id", id)

    private val mapper = RowMapper { row, _ ->
        Project(
            uid = row.getString("uid"),
            adapter = ChatPlatform.valueOf(row.getString("adapter").uppercase()),
            type = ProjectType.valueOf(row.getString("type").uppercase()),
            name = row.getString("name"),
            externalRefId = row.getString("external_ref_id"),
            createdAt = Instant.parse(row.getString("created_at")),
            id = row.getLong("id"),
            channelKind =
                row.getString("channel_kind")?.let { ChannelKind.valueOf(it.uppercase()) },
            isShared = row.getObject("is_shared")?.let { row.getBoolean("is_shared") },
            chatWorkspaceId = row.getString("chat_workspace_id"),
            channelDetailsUpdatedAt =
                row.getString("channel_details_updated_at")?.let(Instant::parse),
        )
    }

    private fun find(column: String, value: Any): Project? =
        sidekickJdbcClient
            .sql("SELECT * FROM projects WHERE $column = :value")
            .param("value", value)
            .query(mapper)
            .optional()
            .orElse(null)

    override fun findByName(
        adapter: ChatPlatform,
        workspaceId: String,
        name: String,
    ): List<Project> =
        sidekickJdbcClient
            .sql(
                """
                SELECT * FROM projects WHERE adapter = :adapter AND chat_workspace_id = :workspace_id
                    AND lower(name) = lower(:name)
                ORDER BY id
                """
                    .trimIndent()
            )
            .param("adapter", adapter.name.lowercase())
            .param("workspace_id", workspaceId)
            .param("name", name)
            .query(mapper)
            .list()

    override fun addIfAbsent(project: Project): Boolean =
        sidekickJdbcClient
            .sql(
                """
                INSERT INTO projects (uid, adapter, type, name, external_ref_id, created_at,
                    channel_kind, is_shared, channel_details_updated_at, chat_workspace_id)
                VALUES (:uid, :adapter, :type, :name, :external_ref_id, :created_at,
                    :channel_kind, :is_shared, :channel_details_updated_at, :chat_workspace_id)
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
            .param("channel_kind", project.channelKind?.name?.lowercase())
            .param("chat_workspace_id", project.chatWorkspaceId)
            .param("is_shared", project.isShared)
            .param("channel_details_updated_at", project.channelDetailsUpdatedAt?.toString())
            .update() == 1

    override fun seedIfEmpty(projects: List<Project>): Boolean =
        sidekickTransactions.execute {
            if (!isEmpty()) return@execute false

            projects.forEach { project ->
                check(addIfAbsent(project)) { "Conflicting project in seed: ${project.uid}" }
            }

            true
        } == true

    override fun updateChannelDetails(project: Project) {
        sidekickJdbcClient
            .sql(
                """
                UPDATE projects SET name = :name, type = :type, channel_kind = :channel_kind,
                    is_shared = :is_shared, channel_details_updated_at = :channel_details_updated_at,
                    chat_workspace_id = :chat_workspace_id
                WHERE uid = :uid
                """
                    .trimIndent()
            )
            .param("uid", project.uid)
            .param("name", project.name)
            .param("type", project.type.name.lowercase())
            .param("chat_workspace_id", project.chatWorkspaceId)
            .param("channel_kind", project.channelKind?.name?.lowercase())
            .param("is_shared", project.isShared)
            .param("channel_details_updated_at", project.channelDetailsUpdatedAt?.toString())
            .update()
    }
}
