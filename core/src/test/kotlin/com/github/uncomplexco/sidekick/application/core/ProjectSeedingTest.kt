package com.github.uncomplexco.sidekick.application.core


import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.testing.sqliteProjectsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ProjectSeedingTest {
    @TempDir lateinit var dir: Path

    private fun config() =
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )

    @ParameterizedTest(name = "{0} startup imports folders {1} and {2}")
    @CsvSource(
        "SLACK, C123, D456, PRIVATE, DM",
        "SLACK, G123, D456, PRIVATE, DM",
        "DISCORD, 123456789012345678, 987654321098765432, PRIVATE, PRIVATE",
    )
    fun `startup registers every persisted channel`(
        platform: ChatPlatform,
        firstFolder: String,
        secondFolder: String,
        firstType: ProjectType,
        secondType: ProjectType,
    ) {
        val config = config()
        val root =
            config.stateDirectoryPath().resolve(platform.name.lowercase()).resolve("channels")
        Files.createDirectories(root.resolve(firstFolder))
        Files.createDirectories(root.resolve(secondFolder))
        val projects = sqliteProjectsRepository(config)

        Projects(projects, config, platform).afterPropertiesSet()

        val persisted = sqliteProjectsRepository(config)
        val first = persisted.findByUid(firstFolder)
        val second = persisted.findByUid(secondFolder)
        assertNotNull(first)
        assertEquals(firstFolder, first.uid)
        assertEquals(firstFolder, first.name)
        assertEquals(firstFolder, first.externalRefId)
        assertEquals(platform, first.adapter)
        assertEquals(firstType, first.type)
        assertNotNull(second)
        assertEquals(secondFolder, second.uid)
        assertEquals(secondFolder, second.name)
        assertEquals(secondFolder, second.externalRefId)
        assertEquals(platform, second.adapter)
        assertEquals(secondType, second.type)
    }

    @Test
    fun `slack startup imports only slack conversation state and ignores workspace folders`() {
        val config = config()
        val root = config.stateDirectoryPath().resolve("slack/channels")
        Files.createDirectories(root.resolve("C123"))
        Files.createDirectories(root.resolve("D456"))
        Files.createDirectories(root.resolve("G789"))
        Files.createDirectories(
            config.stateDirectoryPath().resolve("discord/channels/123456789012345678")
        )
        val workspaces = config.workspaceLayout().projectWorkspacesDirectoryPath()
        Files.createDirectories(workspaces.resolve("C999"))
        Files.createDirectories(workspaces.resolve("987654321098765432"))
        Files.createDirectories(workspaces.resolve("discord/234567890123456789"))
        val projects = sqliteProjectsRepository(config)

        Projects(projects, config, ChatPlatform.SLACK).afterPropertiesSet()

        val persisted = sqliteProjectsRepository(config)
        assertEquals(ChatPlatform.SLACK, persisted.findByUid("C123")?.adapter)
        assertEquals(ChatPlatform.SLACK, persisted.findByUid("D456")?.adapter)
        assertEquals(ChatPlatform.SLACK, persisted.findByUid("G789")?.adapter)
        assertNull(persisted.findByUid("C999"))
        assertNull(persisted.findByUid("123456789012345678"))
        assertNull(persisted.findByUid("987654321098765432"))
        assertNull(persisted.findByUid("discord"))
        assertNull(persisted.findByUid("234567890123456789"))
    }

    @Test
    fun `discord startup imports only discord conversation state and ignores workspace folders`() {
        val config = config()
        val root = config.stateDirectoryPath().resolve("discord/channels")
        Files.createDirectories(config.stateDirectoryPath().resolve("slack/channels/C123"))
        Files.createDirectories(root.resolve("123456789012345678"))
        Files.createDirectories(root.resolve("987654321098765432"))
        val workspaces = config.workspaceLayout().projectWorkspacesDirectoryPath()
        Files.createDirectories(workspaces.resolve("234567890123456789"))
        Files.createDirectories(workspaces.resolve("discord/345678901234567890"))
        val projects = sqliteProjectsRepository(config)

        Projects(projects, config, ChatPlatform.DISCORD).afterPropertiesSet()

        val persisted = sqliteProjectsRepository(config)
        assertEquals(ChatPlatform.DISCORD, persisted.findByUid("123456789012345678")?.adapter)
        assertEquals(ChatPlatform.DISCORD, persisted.findByUid("987654321098765432")?.adapter)
        assertNull(persisted.findByUid("C123"))
        assertNull(persisted.findByUid("discord"))
        assertNull(persisted.findByUid("234567890123456789"))
        assertNull(persisted.findByUid("345678901234567890"))
    }

    @Test
    fun `startup ignores regular files in the persisted channels directory`() {
        val config = config()
        val root = Files.createDirectories(config.stateDirectoryPath().resolve("slack/channels"))
        Files.writeString(root.resolve("notes.txt"), "ignored")
        val projects = sqliteProjectsRepository(config)

        Projects(projects, config, ChatPlatform.SLACK).afterPropertiesSet()

        assertTrue(sqliteProjectsRepository(config).isEmpty())
    }

    @Test
    fun `startup without persisted conversation state leaves the registry empty`() {
        val config = config()
        Files.createDirectories(
            config.workspaceLayout().projectWorkspacesDirectoryPath().resolve("C999")
        )
        val projects = sqliteProjectsRepository(config)

        Projects(projects, config, ChatPlatform.SLACK).afterPropertiesSet()

        assertTrue(sqliteProjectsRepository(config).isEmpty())
    }

    @Test
    fun `startup leaves a nonempty registry unchanged`() {
        val config = config()
        val projects = sqliteProjectsRepository(config)
        projects.addIfAbsent(
            Project(
                "C123",
                ChatPlatform.SLACK,
                "C123",
                "C123",
                Instant.parse("2026-11-08T12:00:00Z"),
            )
        )
        val original = projects.findByUid("C123")
        Files.createDirectories(config.stateDirectoryPath().resolve("slack/channels/C456"))

        Projects(projects, config, ChatPlatform.SLACK).afterPropertiesSet()

        val persisted = sqliteProjectsRepository(config)
        assertNotNull(original)
        assertEquals(original, persisted.findByUid("C123"))
        assertNull(persisted.findByUid("C456"))
    }
}
