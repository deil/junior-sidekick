package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChannelKind
import com.github.uncomplexco.sidekick.application.chat.ChannelVisibility
import com.github.uncomplexco.sidekick.application.chat.ChatChannelDetails
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import com.github.uncomplexco.sidekick.testing.NoOpUsersRepository
import com.github.uncomplexco.sidekick.testing.StubChatAdapter
import com.github.uncomplexco.sidekick.testing.sqliteProjectsRepository
import com.github.uncomplexco.sidekick.testing.userInteractionCoordinator
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ProjectClassificationTest {
    @TempDir lateinit var directory: Path

    private fun config() =
        AgentConfig(
            "Sidekick",
            directory.resolve("state").toString(),
            directory.resolve("workspace").toString(),
        )

    private fun trigger(
        chat: StubChatAdapter,
        channelId: String = "C123",
        type: IncomingMessageType = IncomingMessageType.EXPLICIT_MENTION,
        threadId: String = "1700000000.000",
        messageId: String = "1700000001.000",
    ) =
        TurnTrigger.Incoming(
            IncomingBatch(
                ChatConversationId(channelId, threadId),
                listOf(
                    IncomingMessage(
                        messageId,
                        1000,
                        MessageAuthor("U123", "User"),
                        "<@UBOT> help",
                        type,
                    )
                ),
            ),
            chat,
        )

    @ParameterizedTest(name = "new channel {0} of kind {2} is registered as {3}")
    @CsvSource(
        "C123, PUBLIC, CHANNEL, PUBLIC, false",
        "C123, PRIVATE, CHANNEL, PRIVATE, true",
        "D123, DIRECT_MESSAGE, DM, DM, false",
        "G123, DIRECT_MESSAGE, GROUP_DM, DM, true",
    )
    fun `new project persists the type resolved by the chat adapter`(
        channelId: String,
        visibility: ChannelVisibility,
        kind: ChannelKind,
        expectedType: ProjectType,
        isShared: Boolean,
    ) = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat =
            StubChatAdapter(
                channels =
                    mapOf(channelId to ChatChannelDetails(visibility, kind, isShared, "THOME"))
            )

        // Act
        coordinator.run(trigger(chat, channelId))

        // Assert
        val project = sqliteProjectsRepository(config).findByUid(channelId)
        assertNotNull(project)
        assertEquals(expectedType, project.type)
        assertEquals(kind, project.channelKind)
        assertEquals("THOME", project.chatWorkspaceId)
        assertEquals(isShared, project.isShared)
        assertNotNull(project.channelDetailsUpdatedAt)
        assertFalse(project.isMissingChatMetadata)
        assertEquals(ChatPlatform.SLACK, project.adapter)
        assertEquals(channelId, project.uid)
        assertEquals(listOf(channelId), chat.requestedProjects)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `new thread backfills an existing project without replacing its identity`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        projects.addIfAbsent(
            Project(
                "C123",
                ChatPlatform.SLACK,
                "C123",
                "C123",
                Instant.parse("2026-11-08T12:00:00Z"),
                ProjectType.PUBLIC,
            )
        )
        val original = projects.findByUid("C123")
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PRIVATE,
                                ChannelKind.CHANNEL,
                                true,
                                "THOME",
                            )
                    )
            )

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertNotNull(original)
        val stored = sqliteProjectsRepository(config).findByUid("C123")
        assertNotNull(stored)
        assertEquals(
            original.copy(
                type = ProjectType.PRIVATE,
                channelKind = ChannelKind.CHANNEL,
                chatWorkspaceId = "THOME",
                isShared = true,
                channelDetailsUpdatedAt = stored.channelDetailsUpdatedAt,
            ),
            stored,
        )
        assertNotNull(stored.channelDetailsUpdatedAt)
        assertFalse(stored.isMissingChatMetadata)
        assertEquals(listOf("C123"), chat.requestedProjects)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `unavailable channel details defaults to private without blocking a reply`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat = StubChatAdapter()

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertEquals(ProjectType.PRIVATE, sqliteProjectsRepository(config).findByUid("C123")?.type)
        val project = sqliteProjectsRepository(config).findByUid("C123")
        assertNotNull(project)
        assertNull(project.channelKind)
        assertNull(project.chatWorkspaceId)
        assertNull(project.isShared)
        assertNull(project.channelDetailsUpdatedAt)
        assertTrue(project.isMissingChatMetadata)
        assertEquals(listOf("C123"), chat.requestedProjects)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `new thread refreshes complete project metadata`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val firstChat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PUBLIC,
                                ChannelKind.CHANNEL,
                                false,
                                "THOME",
                            )
                    )
            )
        coordinator.run(trigger(firstChat))
        val original = projects.findByUid("C123")!!
        val secondChat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PRIVATE,
                                ChannelKind.CHANNEL,
                                true,
                                "THOME",
                            )
                    )
            )

        // Act
        coordinator.run(
            trigger(secondChat, threadId = "1700000002.000", messageId = "1700000003.000")
        )

        // Assert
        val stored = sqliteProjectsRepository(config).findByUid("C123")!!
        assertEquals(ProjectType.PRIVATE, stored.type)
        assertEquals(true, stored.isShared)
        assertEquals("THOME", stored.chatWorkspaceId)
        assertEquals(original.id, stored.id)
        assertEquals(original.createdAt, stored.createdAt)
        assertEquals(listOf("C123"), secondChat.requestedProjects)
        assertEquals(listOf("Done"), secondChat.replies)
    }

    @ParameterizedTest(
        name = "continuing a thread with sharing={0} does not refresh channel details"
    )
    @CsvSource(value = ["false", "unknown"], nullValues = ["unknown"])
    fun `continuing a known thread reuses channel details`(initialSharing: Boolean?) = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val firstChat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PUBLIC,
                                ChannelKind.CHANNEL,
                                initialSharing,
                                "THOME",
                            )
                    )
            )
        coordinator.run(trigger(firstChat))
        val original = projects.findByUid("C123")
        val continuationChat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PRIVATE,
                                ChannelKind.CHANNEL,
                                true,
                                "THOME",
                            )
                    )
            )

        // Act
        coordinator.run(trigger(continuationChat, messageId = "1700000002.000"))

        // Assert
        assertEquals(original, sqliteProjectsRepository(config).findByUid("C123"))
        assertTrue(continuationChat.requestedProjects.isEmpty())
        assertEquals(listOf("Done"), continuationChat.replies)
    }

    @Test
    fun `failed refresh keeps existing metadata and allows the new thread`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val firstChat =
            StubChatAdapter(
                channels =
                    mapOf(
                        "C123" to
                            ChatChannelDetails(
                                ChannelVisibility.PUBLIC,
                                ChannelKind.CHANNEL,
                                false,
                                "THOME",
                            )
                    )
            )
        coordinator.run(trigger(firstChat))
        val original = projects.findByUid("C123")
        val secondChat = StubChatAdapter()

        // Act
        coordinator.run(
            trigger(secondChat, threadId = "1700000002.000", messageId = "1700000003.000")
        )

        // Assert
        assertEquals(original, sqliteProjectsRepository(config).findByUid("C123"))
        assertEquals(listOf("C123"), secondChat.requestedProjects)
        assertEquals(listOf("Done"), secondChat.replies)
    }

    @Test
    fun `ignored message does not register or look up a project`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat =
            StubChatAdapter(
                channels = mapOf("C123" to ChatChannelDetails(ChannelVisibility.PUBLIC))
            )

        // Act
        coordinator.run(trigger(chat, type = IncomingMessageType.PASSIVE_MESSAGE))

        // Assert
        assertNull(sqliteProjectsRepository(config).findByUid("C123"))
        assertTrue(chat.requestedProjects.isEmpty())
        assertTrue(chat.replies.isEmpty())
    }
}
