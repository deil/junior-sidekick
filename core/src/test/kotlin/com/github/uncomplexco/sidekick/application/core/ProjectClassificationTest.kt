package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChannelVisibility
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
    ) =
        TurnTrigger.Incoming(
            IncomingBatch(
                ChatConversationId(channelId, "1700000000.000"),
                listOf(
                    IncomingMessage(
                        "1700000001.000",
                        1000,
                        MessageAuthor("U123", "User"),
                        "<@UBOT> help",
                        type,
                    )
                ),
            ),
            chat,
        )

    @ParameterizedTest(name = "new channel {0} is registered as {1} from the adapter")
    @CsvSource(
        "C123, PUBLIC, PUBLIC",
        "C123, PRIVATE, PRIVATE",
        "D123, DIRECT_MESSAGE, DM",
        "G123, DIRECT_MESSAGE, DM",
    )
    fun `new project persists the type resolved by the chat adapter`(
        channelId: String,
        visibility: ChannelVisibility,
        expectedType: ProjectType,
    ) = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat = StubChatAdapter(channelVisibilities = mapOf(channelId to visibility))

        // Act
        coordinator.run(trigger(chat, channelId))

        // Assert
        val project = sqliteProjectsRepository(config).findByUid(channelId)
        assertNotNull(project)
        assertEquals(expectedType, project.type)
        assertEquals(ChatPlatform.SLACK, project.adapter)
        assertEquals(channelId, project.uid)
        assertEquals(listOf(channelId), chat.requestedProjects)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `known project keeps its type without fetching channel details`() = runBlocking {
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
        val chat = StubChatAdapter(channelVisibilities = mapOf("C123" to ChannelVisibility.PRIVATE))

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertNotNull(original)
        assertEquals(original, sqliteProjectsRepository(config).findByUid("C123"))
        assertTrue(chat.requestedProjects.isEmpty())
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
        assertEquals(listOf("C123"), chat.requestedProjects)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `ignored message does not register or look up a project`() = runBlocking {
        // Arrange
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val coordinator =
            userInteractionCoordinator(config, NoOpUsersRepository(), Clock.systemUTC(), projects)
        val chat = StubChatAdapter(channelVisibilities = mapOf("C123" to ChannelVisibility.PUBLIC))

        // Act
        coordinator.run(trigger(chat, type = IncomingMessageType.PASSIVE_MESSAGE))

        // Assert
        assertNull(sqliteProjectsRepository(config).findByUid("C123"))
        assertTrue(chat.requestedProjects.isEmpty())
        assertTrue(chat.replies.isEmpty())
    }
}
