package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.testing.AutomationFixture
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.simple.JdbcClient
import org.sqlite.SQLiteDataSource

class AutomationProjectReferenceTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `existing automations retain their identities and data when project references become primary keys`() {
        // Arrange
        val config =
            AgentConfig(
                "Test",
                dir.resolve("state").toString(),
                dir.resolve("workspace").toString(),
            )
        Files.createDirectories(config.stateDirectoryPath())
        val source =
            SQLiteDataSource().apply {
                url = "jdbc:sqlite:${config.stateDirectoryPath().resolve("sidekick.db")}"
            }
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/sidekick")
            .table("sidekick_schema_history")
            .target("8")
            .load()
            .migrate()
        val jdbc = JdbcClient.create(source)
        jdbc
            .sql(
                """
                INSERT INTO projects (id, uid, adapter, name, external_ref_id, created_at)
                VALUES (41, 'C123', 'slack', 'Project name', 'C123', '2026-11-11T09:00:00Z'),
                    (42, 'C456', 'slack', 'Second project', 'C456', '2026-11-11T09:00:00Z'),
                    (43, 'C789', 'slack', 'Third project', 'C789', '2026-11-11T09:00:00Z')
                """
                    .trimIndent()
            )
            .update()
        jdbc
            .sql(
                "INSERT INTO automation_projects (project_uid, last_allocated_uid) VALUES ('C123', 12), ('C456', 20), ('C789', 30)"
            )
            .update()
        jdbc
            .sql(
                """
                INSERT INTO automations (id, project_uid, uid, name, name_key, description,
                    instructions, trigger, is_enabled, last_run_at, created_at, updated_at)
                VALUES (71, 'C123', 7, 'Report', 'report', 'Daily report', 'Summarize alerts',
                    '{"type":"scheduled","timezone":"UTC","schedule":"0 9 * * *"}',
                    1, '2026-11-11T09:00:00Z', '2026-11-10T09:00:00Z', '2026-11-11T09:00:00Z'),
                    (80, 'C789', 3, 'Other', 'other', NULL, 'Other instructions',
                    '{"type":"external"}', 0, NULL, '2026-11-10T09:00:00Z', '2026-11-10T09:00:00Z')
                """
                    .trimIndent()
            )
            .update()

        // Act
        val migrated = AutomationFixture(config)
        val listed = migrated.service.list("C123")

        // Assert
        assertEquals(
            listOf(
                Automation(
                    id = 71L,
                    projectId = 41L,
                    uid = 7,
                    name = "Report",
                    description = "Daily report",
                    instructions = "Summarize alerts",
                    trigger = scheduledTrigger("0 9 * * *", "UTC"),
                    enabled = true,
                    lastRunAt = Instant.parse("2026-11-11T09:00:00Z"),
                    createdAt = Instant.parse("2026-11-10T09:00:00Z"),
                    updatedAt = Instant.parse("2026-11-11T09:00:00Z"),
                )
            ),
            listed,
        )
        assertEquals("Project name", migrated.projects.getById(41L).name)
        assertTrue(migrated.repository.isImported(migrated.otherProjectId))
        assertTrue(migrated.service.list("C456").isEmpty())
        assertEquals(80L, migrated.service.list("C789").single().id)
        assertEquals(
            13,
            migrated.service
                .create(
                    "C123",
                    CreateAutomation(
                        "New",
                        null,
                        "Instructions",
                        scheduledTrigger("0 9 * * *", "UTC"),
                    ),
                )
                .uid,
        )
    }
}
