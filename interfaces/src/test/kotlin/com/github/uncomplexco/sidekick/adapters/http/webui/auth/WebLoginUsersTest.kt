package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteConfiguration
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteUsersRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.User
import com.github.uncomplexco.sidekick.ports.UsersRepository
import com.github.uncomplexco.sidekick.testing.WebAuthFixture
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource

class WebLoginUsersTest {
    @TempDir lateinit var directory: Path
    private lateinit var context: AnnotationConfigApplicationContext
    private lateinit var users: UsersRepository

    @BeforeEach
    fun setup() {
        context =
            AnnotationConfigApplicationContext().apply {
                environment.propertySources.addFirst(
                    MapPropertySource(
                        "web-login-test",
                        mapOf(
                            "agent.name" to "Sidekick",
                            "agent.state-directory" to directory.resolve("state").toString(),
                            "agent.working-directory" to directory.resolve("workspace").toString(),
                        ),
                    )
                )
                register(
                    AgentConfig::class.java,
                    SqliteConfiguration::class.java,
                    SqliteUsersRepository::class.java,
                )
                refresh()
            }
        users = context.getBean(UsersRepository::class.java)
    }

    private fun loginFixture() =
        WebAuthFixture(users).apply {
            now = Instant.parse("2026-11-08T12:00:00Z")
        }

    @AfterEach fun close() = context.close()

    @Test
    fun `successful web login persists the IdP profile without a chat identity`() {
        // Arrange
        val fixture = loginFixture()

        // Act
        val login = fixture.completeLogin(name = "Ada Lovelace", email = "ada@example.com")

        // Assert
        assertTrue(
            checkNotNull(login.response.redirectedUrl)
                .startsWith("https://frontend.test/login?code=")
        )
        val user = users.findAllByIdpId("google-test-subject").single()
        assertNotNull(user.id)
        assertEquals("Ada Lovelace", user.name)
        assertEquals("ada@example.com", user.email)
        assertEquals("google-test-subject", user.idpId)
        assertNull(user.chatId)
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), user.createdAt)
        assertEquals(Instant.parse("2026-11-08T12:00:00Z"), user.lastSeenAt)
    }

    @Test
    fun `repeat web login updates last seen without creating another user`() {
        // Arrange
        val fixture = loginFixture()
        fixture.completeLogin()
        val original = users.findAllByIdpId("google-test-subject").single()
        fixture.now = Instant.parse("2026-11-09T12:00:00Z")

        // Act
        val login = fixture.completeLogin()

        // Assert
        assertTrue(
            checkNotNull(login.response.redirectedUrl)
                .startsWith("https://frontend.test/login?code=")
        )
        val stored = users.findAllByIdpId("google-test-subject").single()
        assertEquals(original.copy(lastSeenAt = Instant.parse("2026-11-09T12:00:00Z")), stored)
    }

    @Test
    fun `existing IdP subject can sign in after changing email`() {
        // Arrange
        val fixture = loginFixture()
        fixture.completeLogin()
        val original = users.findAllByIdpId("google-test-subject").single()
        users.linkChatId("person@example.com", "U123", Instant.parse("2026-11-08T12:00:00Z"))
        fixture.now = Instant.parse("2026-11-09T12:00:00Z")

        // Act
        val login = fixture.completeLogin(email = "renamed@example.com")

        // Assert
        assertTrue(
            checkNotNull(login.response.redirectedUrl)
                .startsWith("https://frontend.test/login?code=")
        )
        assertEquals(
            original.copy(
                email = "renamed@example.com",
                chatId = "U123",
                lastSeenAt = Instant.parse("2026-11-09T12:00:00Z"),
            ),
            users.findAllByIdpId("google-test-subject").single(),
        )
        assertNull(users.findByEmail("person@example.com"))
        assertEquals("renamed@example.com", users.findByChatId("U123")?.email)
    }

    @Test
    fun `changed IdP email belonging to another user rejects login without changing either row`() {
        // Arrange
        val fixture = loginFixture()
        fixture.completeLogin()
        val original = users.findAllByIdpId("google-test-subject").single()
        users.add(
            User(
                name = "Another Person",
                email = "another@example.com",
                idpId = "another-subject",
                createdAt = Instant.parse("2026-11-01T12:00:00Z"),
                lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
            )
        )
        val other = users.findAllByIdpId("another-subject").single()
        fixture.now = Instant.parse("2026-11-09T12:00:00Z")

        // Act
        val login = fixture.completeLogin(email = "another@example.com")

        // Assert
        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertEquals(original, users.findAllByIdpId("google-test-subject").single())
        assertEquals(other, users.findAllByIdpId("another-subject").single())
    }

    @Test
    fun `web login links the IdP identity to an existing chat user by email`() {
        // Arrange
        val fixture = loginFixture()
        users.add(
            User(
                "Chat Person",
                "person@example.com",
                null,
                Instant.parse("2026-11-01T12:00:00Z"),
                chatId = "U123",
            )
        )
        val original = users.findByChatId("U123")

        // Act
        val login = fixture.completeLogin()

        // Assert
        assertTrue(
            checkNotNull(login.response.redirectedUrl)
                .startsWith("https://frontend.test/login?code=")
        )
        assertNotNull(original)
        assertEquals(
            original.copy(
                idpId = "google-test-subject",
                lastSeenAt = Instant.parse("2026-11-08T12:00:00Z"),
            ),
            users.findAllByIdpId("google-test-subject").single(),
        )
    }

    @Test
    fun `web login without a display name stores the email as the name`() {
        // Arrange
        val fixture = loginFixture()

        // Act
        val login = fixture.completeLogin(name = null)

        // Assert
        assertTrue(
            checkNotNull(login.response.redirectedUrl)
                .startsWith("https://frontend.test/login?code=")
        )
        assertEquals(
            "person@example.com",
            users.findAllByIdpId("google-test-subject").single().name,
        )
    }

    @Test
    fun `web login from a forbidden email domain does not create a user`() {
        // Arrange
        val fixture = loginFixture()

        // Act
        val login = fixture.completeLogin(email = "person@other.example")

        // Assert
        assertEquals("https://frontend.test/login?error=domain", login.response.redirectedUrl)
        assertTrue(users.findAllByIdpId("google-test-subject").isEmpty())
    }

    @Test
    fun `web login with an unverified email does not create a user`() {
        // Arrange
        val fixture = loginFixture()

        // Act
        val login = fixture.completeLogin(verified = false)

        // Assert
        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertTrue(users.findAllByIdpId("google-test-subject").isEmpty())
    }

    @Test
    fun `web login without an email does not create a user`() {
        // Arrange
        val fixture = loginFixture()

        // Act
        val login = fixture.completeLogin(email = null)

        // Assert
        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertTrue(users.findAllByIdpId("google-test-subject").isEmpty())
    }

    @Test
    fun `forbidden domain login leaves an existing user identity and activity unchanged`() {
        // Arrange
        val fixture = loginFixture()
        users.add(
            User(
                name = "Existing Person",
                email = "person@other.example",
                idpId = "previous-subject",
                chatId = "U123",
                createdAt = Instant.parse("2026-11-01T12:00:00Z"),
                lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
            )
        )
        val original = users.findByChatId("U123")

        // Act
        val login = fixture.completeLogin(email = "person@other.example")

        // Assert
        assertEquals("https://frontend.test/login?error=domain", login.response.redirectedUrl)
        assertNotNull(original)
        assertEquals(original, users.findByChatId("U123"))
        assertTrue(users.findAllByIdpId("google-test-subject").isEmpty())
    }

    @Test
    fun `unverified login leaves an existing user identity and activity unchanged`() {
        // Arrange
        val fixture = loginFixture()
        users.add(
            User(
                name = "Existing Person",
                email = "person@example.com",
                idpId = "previous-subject",
                chatId = "U123",
                createdAt = Instant.parse("2026-11-01T12:00:00Z"),
                lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
            )
        )
        val original = users.findByChatId("U123")

        // Act
        val login = fixture.completeLogin(verified = false)

        // Assert
        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertNotNull(original)
        assertEquals(original, users.findByChatId("U123"))
        assertTrue(users.findAllByIdpId("google-test-subject").isEmpty())
    }

    @Test
    fun `login without email leaves an existing IdP user unchanged`() {
        // Arrange
        val fixture = loginFixture()
        users.add(
            User(
                name = "Existing Person",
                email = "person@example.com",
                idpId = "google-test-subject",
                chatId = "U123",
                createdAt = Instant.parse("2026-11-01T12:00:00Z"),
                lastSeenAt = Instant.parse("2026-11-07T12:00:00Z"),
            )
        )
        val original = users.findAllByIdpId("google-test-subject").single()

        // Act
        val login = fixture.completeLogin(email = null)

        // Assert
        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertEquals(original, users.findAllByIdpId("google-test-subject").single())
    }
}
