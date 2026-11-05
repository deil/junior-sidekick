package com.github.uncomplexco.sidekick.adapters.http.webui

import com.github.uncomplexco.sidekick.adapters.http.webui.auth.UserApiSecurityConfiguration
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.VerifiedEmailIdentity
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.WebAuthConfiguration
import com.github.uncomplexco.sidekick.adapters.slack.DeleteSlackMessageUsecase
import com.github.uncomplexco.sidekick.testing.SlackDeletionApi
import com.github.uncomplexco.sidekick.testing.WebAuthFixture
import jakarta.servlet.http.Cookie
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockServletContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

class MessageDeletionControllerTest {
    private lateinit var api: SlackDeletionApi
    private lateinit var context: AnnotationConfigWebApplicationContext
    private lateinit var mvc: MockMvc
    private val fixture = WebAuthFixture()
    private val bearer
        get() =
            fixture.tokens.issue(VerifiedEmailIdentity("google:user", "operator@example.com")).value

    @BeforeEach
    fun setup() {
        context =
            AnnotationConfigWebApplicationContext().apply {
                servletContext = MockServletContext()
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    this,
                    "webui.auth.jwt-signing-key=${fixture.properties.jwtSigningKey}",
                    "webui.auth.allowed-email-domain=example.com",
                )
                register(TestConfiguration::class.java)
                refresh()
            }
        api = context.getBean(SlackDeletionApi::class.java)
        mvc =
            MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @AfterEach
    fun close() {
        context.close()
    }

    @Test
    fun `authenticated operator deletes the linked reply instead of the parent thread`() {
        mvc.post("/api/web/user/messages/delete") {
                header("Authorization", "Bearer $bearer")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"message_url":"https://workspace.slack.com/archives/C123ABC/p1700000000001234?thread_ts=1699999999.000001&cid=C123ABC"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("deleted") }
                header { string("Cache-Control", "no-store") }
            }

        val request = api.requests.single()
        assertEquals("/api/chat.delete", request.path)
        assertEquals("Bearer xoxb-deletion-test", request.authorization)
        val fields =
            request.body.split('&').associate {
                val (key, value) = it.split('=', limit = 2)
                key to URLDecoder.decode(value, UTF_8)
            }
        assertEquals("C123ABC", fields["channel"])
        assertEquals("1700000000.001234", fields["ts"])
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "https://workspace.slack.com/archives/C123ABC/p1700000000001234",
                "https://workspace.slack.com/archives/G123ABC/p1700000000001234",
                "https://workspace.slack.com/archives/D123ABC/p1700000000001234",
            ]
    )
    fun `Slack message links support public private and direct conversations`(link: String) {
        mvc.post("/api/web/user/messages/delete") {
                header("Authorization", "Bearer $bearer")
                contentType = MediaType.APPLICATION_JSON
                content = """{"message_url":"$link"}"""
            }
            .andExpect { status { isOk() } }

        assertEquals(1, api.requests.size)
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "not a url",
                "https://workspace.slack.com.evil.example/archives/C123ABC/p1700000000001234",
                "https://example.com/archives/C123ABC/p1700000000001234",
                "http://workspace.slack.com/archives/C123ABC/p1700000000001234",
                "https://workspace.slack.com/archives/C123ABC",
                "https://workspace.slack.com/archives/C123ABC/p123",
            ]
    )
    fun `invalid links are rejected before calling Slack`(link: String) {
        mvc.post("/api/web/user/messages/delete") {
                header("Authorization", "Bearer $bearer")
                contentType = MediaType.APPLICATION_JSON
                content = """{"message_url":"$link"}"""
            }
            .andExpect { status { isBadRequest() } }

        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `browser cookies cannot authorize deletion`() {
        mvc.post("/api/web/user/messages/delete") {
                cookie(Cookie("sidekick-encore", "some-browser-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"message_url":"https://workspace.slack.com/archives/C123ABC/p1700000000001234"}"""
            }
            .andExpect { status { isUnauthorized() } }

        assertTrue(api.requests.isEmpty())
    }

    @ParameterizedTest(name = "Slack {0} maps deletion to HTTP {1}")
    @CsvSource(
        "cant_delete_message,403",
        "message_not_found,404",
        "channel_not_found,404",
        "ratelimited,429",
        "missing_scope,503",
        "internal_error,503",
    )
    fun `Slack rejection never reports successful deletion`(error: String, status: Int) {
        api.responseBody = """{"ok":false,"error":"$error"}"""

        mvc.post("/api/web/user/messages/delete") {
                header("Authorization", "Bearer $bearer")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"message_url":"https://workspace.slack.com/archives/C123ABC/p1700000000001234"}"""
            }
            .andExpect { status { isEqualTo(status) } }

        assertEquals(1, api.requests.size)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(
        WebAuthConfiguration::class,
        UserApiSecurityConfiguration::class,
        WebUiHttpConfiguration::class,
        MessageDeletionController::class,
        DeleteSlackMessageUsecase::class,
    )
    class TestConfiguration {
        @Bean(destroyMethod = "close") fun slackApi() = SlackDeletionApi()

        @Bean fun slackApp(api: SlackDeletionApi) = api.app
    }
}
