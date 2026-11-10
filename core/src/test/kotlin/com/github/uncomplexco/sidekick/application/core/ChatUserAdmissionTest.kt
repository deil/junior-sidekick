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
        val chat = StubChatAdapter(mapOf("U123" to ChatUserDetails("Ada", "ada@example.com")))

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
    fun `known chat user updates last seen without fetching profile again`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(
            User(
                "Ada",
                "ada@example.com",
                null,
                Instant.parse("2026-11-01T12:00:00Z"),
                chatId = "U123",
                lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
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
    fun `new chat identity links to an existing web user with the same email`() = runBlocking {
        // Arrange
        val config = config()
        val users = sqliteUsersRepository(config)
        users.add(
            User("Web Ada", "ada@example.com", "google-ada", Instant.parse("2026-11-01T12:00:00Z"))
        )
        val original = users.findAllByIdpId("google-ada").single()
        val coordinator = userInteractionCoordinator(config, users, clock)
        val chat = StubChatAdapter(mapOf("U123" to ChatUserDetails("Slack Ada", "ada@example.com")))

        // Act
        coordinator.run(trigger(chat))

        // Assert
        assertEquals(
            original.copy(chatId = "U123", lastSeenAt = now),
            sqliteUsersRepository(config).findByChatId("U123"),
        )
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
