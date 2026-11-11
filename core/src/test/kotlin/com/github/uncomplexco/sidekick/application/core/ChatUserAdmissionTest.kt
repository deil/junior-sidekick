package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatUserDetails
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.chat.SteeringInbox
import com.github.uncomplexco.sidekick.application.chat.SteeringInput
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import com.github.uncomplexco.sidekick.testing.StubChatAdapter
import com.github.uncomplexco.sidekick.testing.sqliteUsersRepository
import com.github.uncomplexco.sidekick.testing.userInteractionCoordinator
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ChatUserAdmissionTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun config() =
        AgentConfig(
            "Sidekick",
            directory.resolve("state").toString(),
            directory.resolve("workspace").toString(),
        )

    private fun message(
        userId: String = "U123",
        type: IncomingMessageType = IncomingMessageType.EXPLICIT_MENTION,
    ) =
        IncomingMessage(
            "1700000000.000",
            1000,
            MessageAuthor(userId, "Incoming display name"),
            "<@UBOT> help",
            type,
        )

    private fun trigger(chat: StubChatAdapter, message: IncomingMessage = message()) =
        TurnTrigger.Incoming(IncomingBatch(ChatConversationId("C123"), listOf(message)), chat)

    @Test
    fun `admitted chat user is provisioned from adapter details`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf("U123" to ChatUserDetails("Ada", "ada@example.com", "THOME", false, true))
            )

        // Act
        coordinator.run(trigger(chat))

        // Assert
        val user = sqliteUsersRepository(config).findByChatId("U123")
        assertNotNull(user)
        assertNotNull(user.id)
        assertEquals("Ada", user.name)
        assertEquals("ada@example.com", user.email)
        assertEquals("U123", user.chatId)
        assertNull(user.idpId)
        assertEquals(now, user.createdAt)
        assertEquals(now, user.lastSeenAt)
        assertEquals("THOME", user.chatWorkspaceId)
        assertEquals(false, user.isBot)
        assertEquals(true, user.isGuest)
        assertEquals(now, user.chatDetailsUpdatedAt)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `ignored message does not provision or look up chat details`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat = StubChatAdapter(mapOf("U123" to ChatUserDetails("Ada", "ada@example.com")))

        // Act
        coordinator.run(trigger(chat, message(type = IncomingMessageType.PASSIVE_MESSAGE)))

        // Assert
        assertNull(sqliteUsersRepository(config).findByChatId("U123"))
        assertTrue(chat.requestedUsers.isEmpty())
        assertTrue(chat.replies.isEmpty())
    }

    @Test
    fun `known chat user with complete metadata does not fetch profile even without email`() =
        runBlocking {
            // Arrange
            val config = config()
            val users = sqliteUsersRepository(config)
            users.add(
                User(
                    "Ada",
                    null,
                    null,
                    Instant.parse("2026-11-01T12:00:00Z"),
                    chatId = "U123",
                    lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
                    chatWorkspaceId = "THOME",
                    isBot = false,
                    isGuest = false,
                    chatDetailsUpdatedAt = Instant.parse("2026-11-01T12:00:00Z"),
                )
            )
            val original = users.findByChatId("U123")
            val coordinator = userInteractionCoordinator(config, users, clock)
            val chat = StubChatAdapter()

            // Act
            coordinator.run(trigger(chat))

            // Assert
            assertNotNull(original)
            assertEquals(
                original.copy(lastSeenAt = now),
                sqliteUsersRepository(config).findByChatId("U123"),
            )
            assertTrue(chat.requestedUsers.isEmpty())
            assertEquals(listOf("Done"), chat.replies)
        }

    @Test
    fun `failed metadata lookup preserves user and records activity`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(User("Ada", "ada@example.com", "google-ada", Instant.EPOCH, chatId = "U123"))
        val original = users.findByChatId("U123")!!
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat = StubChatAdapter()

        // Act
        coordinator.run(trigger(chat))

        // Assert
        val stored = sqliteUsersRepository(config).findByChatId("U123")
        assertEquals(original.copy(lastSeenAt = now), stored)
        assertTrue(stored!!.isMissingChatMetadata)
        assertEquals(listOf("U123"), chat.requestedUsers)
        assertEquals(listOf("Done"), chat.replies)
    }

    @ParameterizedTest(
        name = "backfill workspace={0}, bot={1}, guest={2} without replacing user identity"
    )
    @CsvSource(
        ", , ",
        ", false, false",
        "THOME, , false",
        "THOME, false, ",
    )
    fun `missing metadata is backfilled on the next interaction`(
        workspaceId: String?,
        isBot: Boolean?,
        isGuest: Boolean?,
    ) = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(
            User(
                "Web Ada",
                "ada@example.com",
                "google-ada",
                Instant.EPOCH,
                chatId = "U123",
                chatWorkspaceId = workspaceId,
                isBot = isBot,
                isGuest = isGuest,
            )
        )
        val original = users.findByChatId("U123")!!
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf(
                    "U123" to
                        ChatUserDetails("Slack Ada", "different@example.com", "THOME", false, false)
                )
            )

        // Act
        coordinator.run(trigger(chat))

        // Assert
        val stored = sqliteUsersRepository(config).findByChatId("U123")
        assertEquals(
            original.copy(
                chatWorkspaceId = "THOME",
                isBot = false,
                isGuest = false,
                chatDetailsUpdatedAt = now,
                lastSeenAt = now,
            ),
            stored,
        )
        assertEquals(false, stored!!.isMissingChatMetadata)
        assertEquals(listOf("U123"), chat.requestedUsers)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `partial lookup preserves known metadata and remains incomplete`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(User("Ada", null, null, Instant.EPOCH, chatId = "U123", isBot = false))
        val original = users.findByChatId("U123")!!
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat = StubChatAdapter(mapOf("U123" to ChatUserDetails("Ada", null, "THOME")))

        // Act
        coordinator.run(trigger(chat))

        // Assert
        val stored = sqliteUsersRepository(config).findByChatId("U123")
        assertEquals(
            original.copy(chatWorkspaceId = "THOME", chatDetailsUpdatedAt = now, lastSeenAt = now),
            stored,
        )
        assertTrue(stored!!.isMissingChatMetadata)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `new chat identity links to an existing web user with the same email`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(
            User("Web Ada", "ada@example.com", "google-ada", Instant.parse("2026-11-01T12:00:00Z"))
        )
        val original = users.findAllByIdpId("google-ada").single()
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf(
                    "U123" to ChatUserDetails("Slack Ada", "ada@example.com", "THOME", false, false)
                )
            )

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertEquals(
            original.copy(
                chatId = "U123",
                lastSeenAt = now,
                chatWorkspaceId = "THOME",
                isBot = false,
                isGuest = false,
                chatDetailsUpdatedAt = now,
            ),
            sqliteUsersRepository(config).findByChatId("U123"),
        )
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `new bot without email is persisted with chat metadata`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf("U123" to ChatUserDetails("Build bot", null, "TEXTERNAL", true, false))
            )

        // Act
        coordinator.run(trigger(chat))

        // Assert
        val user = sqliteUsersRepository(config).findByChatId("U123")
        assertNotNull(user)
        assertNull(user.email)
        assertEquals("TEXTERNAL", user.chatWorkspaceId)
        assertEquals(true, user.isBot)
        assertEquals(false, user.isGuest)
        assertEquals(now, user.chatDetailsUpdatedAt)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `authors without emails remain separate users with unknown chat metadata`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf(
                    "U123" to ChatUserDetails("Ada", null),
                    "U456" to ChatUserDetails("Grace", null),
                )
            )
        val batch =
            IncomingBatch(
                ChatConversationId("C123", "1700000000.000"),
                listOf(
                    message(),
                    message("U456").copy(id = "1700000001.000"),
                ),
            )

        // Act
        coordinator.run(TurnTrigger.Incoming(batch, chat))

        // Assert
        val persisted = sqliteUsersRepository(config)
        val ada = persisted.findByChatId("U123")
        val grace = persisted.findByChatId("U456")
        assertNotNull(ada)
        assertNotNull(grace)
        assertEquals("Ada", ada.name)
        assertEquals("Grace", grace.name)
        assertNull(ada.email)
        assertNull(grace.email)
        assertNull(ada.chatWorkspaceId)
        assertNull(ada.isBot)
        assertNull(ada.isGuest)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `missing adapter details leaves user unprovisioned and allows the turn`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat = StubChatAdapter()

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertNull(sqliteUsersRepository(config).findByChatId("U123"))
        assertEquals(listOf("U123"), chat.requestedUsers)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `steering messages provision their authors too`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator =
            userInteractionCoordinator(config, users, clock) { steering ->
                steering.drain()
            }
        val chat =
            StubChatAdapter(
                mapOf(
                    "U123" to ChatUserDetails("Ada", "ada@example.com"),
                    "U456" to ChatUserDetails("Grace", "grace@example.com"),
                )
            )
        val steering = SteeringInbox {
            SteeringInput.Messages(listOf(message("U456").copy(id = "1700000001.000")))
        }

        // Act
        coordinator.run(trigger(chat), steering)

        // Assert
        val persisted = sqliteUsersRepository(config)
        assertEquals("ada@example.com", persisted.findByChatId("U123")?.email)
        assertEquals("grace@example.com", persisted.findByChatId("U456")?.email)
        assertEquals(now, persisted.findByChatId("U456")?.lastSeenAt)
        assertEquals(listOf("Done"), chat.replies)
    }

    @Test
    fun `initial admitted batch provisions every author`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat =
            StubChatAdapter(
                mapOf(
                    "U123" to ChatUserDetails("Ada", "ada@example.com"),
                    "U456" to ChatUserDetails("Grace", "grace@example.com"),
                )
            )
        val batch =
            IncomingBatch(
                ChatConversationId("C123", "1700000000.000"),
                listOf(
                    message(),
                    message("U456").copy(id = "1700000001.000"),
                ),
            )

        // Act
        coordinator.run(TurnTrigger.Incoming(batch, chat))

        // Assert
        val persisted = sqliteUsersRepository(config)
        assertEquals("ada@example.com", persisted.findByChatId("U123")?.email)
        assertEquals("grace@example.com", persisted.findByChatId("U456")?.email)
        assertEquals(now, persisted.findByChatId("U123")?.lastSeenAt)
        assertEquals(now, persisted.findByChatId("U456")?.lastSeenAt)
        assertEquals(listOf("Done"), chat.replies)
    }
}
