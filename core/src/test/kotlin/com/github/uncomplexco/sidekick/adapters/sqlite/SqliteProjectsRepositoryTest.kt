package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Project
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.testing.sqliteProjectsRepository
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteProjectsRepositoryTest {
    @TempDir lateinit var dir: Path

    private fun config() =
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )

    @Test
    fun `name lookup matches case insensitively within its platform and workspace`() {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val base =
            Project(
                "C123",
                ChatPlatform.SLACK,
                "Platform",
                "C123",
                Instant.EPOCH,
                chatWorkspaceId = "T123",
            )
        projects.addIfAbsent(base)
        projects.addIfAbsent(
            base.copy(uid = "C456", externalRefId = "C456", chatWorkspaceId = "T456")
        )
        projects.addIfAbsent(
            base.copy(uid = "discord", externalRefId = "discord", adapter = ChatPlatform.DISCORD)
        )

        // Act
        val found =
            sqliteProjectsRepository(config).findByName(ChatPlatform.SLACK, "T123", "platform")

        // Assert
        assertEquals(listOf("C123"), found.map { it.uid })
    }

    @Test
    fun `adding a project persists each field`() {
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val project =
            Project(
                uid = "slack-C123",
                adapter = ChatPlatform.SLACK,
                type = ProjectType.PUBLIC,
                name = "Engineering",
                externalRefId = "C123",
                createdAt = Instant.parse("2026-11-08T12:00:00Z"),
            )

        val inserted = projects.addIfAbsent(project)

        val persisted = sqliteProjectsRepository(config).findByUid("slack-C123")
        assertTrue(inserted)
        assertNotNull(persisted)
        assertNotNull(persisted.id)
        assertEquals("slack-C123", persisted.uid)
        assertEquals(ChatPlatform.SLACK, persisted.adapter)
        assertEquals(ProjectType.PUBLIC, persisted.type)
        assertEquals("Engineering", persisted.name)
        assertEquals("C123", persisted.externalRefId)
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), persisted.createdAt)
    }

    @Test
    fun `registering an existing project preserves its original values`() {
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val project =
            Project(
                "C123",
                ChatPlatform.SLACK,
                "C123",
                "C123",
                Instant.parse("2026-11-08T12:00:00Z"),
            )
        projects.addIfAbsent(project)
        val original = projects.findByUid("C123")

        val inserted = projects.addIfAbsent(project.copy(name = "replacement"))

        assertFalse(inserted)
        assertNotNull(original)
        assertEquals(original, sqliteProjectsRepository(config).findByUid("C123"))
    }
}
