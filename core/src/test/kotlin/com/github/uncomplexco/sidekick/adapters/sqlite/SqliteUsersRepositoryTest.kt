package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.User
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.simple.JdbcClient
import org.sqlite.SQLiteDataSource

class SqliteUsersRepositoryTest {
    @TempDir lateinit var dir: Path

    private val config by lazy {
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )
    }
    private val jdbc by lazy {
        Files.createDirectories(config.stateDirectoryPath())
        val configuration = SqliteConfiguration()
        configuration.sidekickJdbcClient(configuration.sidekickDataSource(config))
    }
    private val users by lazy { SqliteUsersRepository(jdbc) }
    private val user =
        User(
            name = "Alice",
            email = "alice@example.com",
            idpId = "google-alice",
            chatId = "UALICE",
            createdAt = Instant.EPOCH,
        )

    @Test
    fun `new users default to non admin`() {
        assertFalse(user.isAdmin)

        users.add(user)

        assertFalse(assertNotNull(users.findByEmail("alice@example.com")).isAdmin)
    }

    @Test
    fun `adding a user cannot grant admin access`() {
        val requested = user.copy(isAdmin = true)

        users.add(requested)

        assertFalse(assertNotNull(users.findByEmail("alice@example.com")).isAdmin)
    }

    @Test
    fun `direct database changes are read by every user lookup`() {
        users.add(user)
        jdbc.sql("UPDATE users SET is_admin = 1 WHERE email = 'alice@example.com'").update()

        val byEmail = assertNotNull(users.findByEmail("alice@example.com"))
        val byChat = assertNotNull(users.findByChatId("UALICE"))
        val byIdp = users.findAllByIdpId("google-alice").single()

        assertTrue(byEmail.isAdmin)
        assertEquals(byEmail, byChat)
        assertEquals(byEmail, byIdp)
    }

    @Test
    fun `updating chat details preserves database managed admin status`() {
        users.add(user)
        jdbc.sql("UPDATE users SET is_admin = 1 WHERE email = 'alice@example.com'").update()
        val updated =
            user.copy(chatWorkspaceId = "T123", lastSeenAt = Instant.EPOCH.plusSeconds(60))

        users.updateChatDetails("UALICE", updated)

        val persisted = assertNotNull(users.findByChatId("UALICE"))
        assertTrue(persisted.isAdmin)
        assertEquals("T123", persisted.chatWorkspaceId)
        assertEquals(Instant.EPOCH.plusSeconds(60), persisted.lastSeenAt)
    }

    @Test
    fun `migration defaults existing users to non admin without changing their data`() {
        Files.createDirectories(config.stateDirectoryPath())
        val databasePath = config.stateDirectoryPath().resolve("sidekick.db")
        val source =
            SQLiteDataSource().apply {
                url = "jdbc:sqlite:$databasePath"
            }
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/sidekick")
            .table("sidekick_schema_history")
            .target("9")
            .load()
            .migrate()
        JdbcClient.create(source)
            .sql(
                """
                INSERT INTO users (name, email, idp_id, chat_id, created_at)
                VALUES ('Alice', 'alice@example.com', 'google-alice', 'UALICE', '1970-01-01T00:00:00Z')
                """
                    .trimIndent()
            )
            .update()

        val persisted = assertNotNull(users.findByEmail("alice@example.com"))

        assertNotNull(persisted.id)
        assertEquals(user.copy(id = persisted.id), persisted)
    }
}
