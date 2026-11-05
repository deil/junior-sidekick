package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.github.uncomplexco.sidekick.adapters.http.webui.SidekickProfileController
import com.github.uncomplexco.sidekick.adapters.http.webui.UserProfileController
import com.github.uncomplexco.sidekick.adapters.http.webui.WebUiHttpConfiguration
import com.github.uncomplexco.sidekick.adapters.slack.SlackBotProfile
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.testing.WebAuthFixture
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Duration
import java.util.Base64
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

class BrowserSignInSecurityTest {
    private lateinit var context: AnnotationConfigWebApplicationContext
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        val config = WebAuthFixture().properties
        context =
            AnnotationConfigWebApplicationContext().apply {
                servletContext = MockServletContext()
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    this,
                    "webui.auth.google-client-id=${config.googleClientId}",
                    "webui.auth.google-client-secret=${config.googleClientSecret}",
                    "webui.auth.allowed-email-domain=example.com",
                    "webui.auth.jwt-signing-key=${config.jwtSigningKey}",
                )
                register(TestConfiguration::class.java)
                refresh()
            }
        mvc =
            MockMvcBuilders.webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(
                    springSecurity()
                )
                .build()
    }

    @AfterEach fun close() = context.close()

    @Test
    fun `Spring starts Google OIDC authorization and retains only a temporary login transaction`() {
        val fixture = WebAuthFixture()

        val result =
            mvc.get("/oauth2/authorization/google") {
                    param("sidekick_code_challenge", fixture.challenge)
                    param("sidekick_state", fixture.state)
                }
                .andExpect {
                    status { is3xxRedirection() }
                    redirectedUrlPattern("https://accounts.google.com/**")
                }
                .andReturn()

        assertNotNull(result.request.getSession(false))
    }

    @Test
    fun `token exchange accepts JSON without a login cookie or CSRF token`() {
        mvc.post("/api/web/auth/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"invalid","code_verifier":"${"x".repeat(43)}"}"""
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `user profile rejects a request without a bearer token`() {
        mvc.get("/api/web/user/profile").andExpect {
            status { isUnauthorized() }
            header { doesNotExist("Location") }
        }
    }

    @Test
    fun `user profile returns the authenticated email rather than bot metadata`() {
        val fixture = WebAuthFixture()
        val token =
            SidekickJwtTokens(fixture.properties, Clock.systemUTC())
                .issue(VerifiedEmailIdentity("google:subject", "verified-user@example.com"))
                .value

        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                content {
                    json(
                        """{"email":"verified-user@example.com"}""",
                        org.springframework.test.json.JsonCompareMode.STRICT,
                    )
                }
            }
    }

    @Test
    fun `a servlet login session does not authenticate the user API`() {
        val loginSession =
            MockHttpSession().apply {
                setAttribute(
                    "SPRING_SECURITY_CONTEXT",
                    SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                            "person@example.com",
                            null,
                            emptyList(),
                        )
                    ),
                )
            }

        mvc.get("/api/web/user/profile") { session = loginSession }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `user API rejects an access token signed by a different key`() {
        val config =
            WebAuthFixture()
                .properties
                .copy(jwtSigningKey = Base64.getEncoder().encodeToString(ByteArray(32) { 24 }))
        val token =
            SidekickJwtTokens(config, Clock.systemUTC())
                .issue(VerifiedEmailIdentity("subject", "person@example.com"))
                .value

        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `user API rejects an expired access token`() {
        val token =
            SidekickJwtTokens(
                    WebAuthFixture().properties,
                    Clock.offset(Clock.systemUTC(), Duration.ofHours(-25)),
                )
                .issue(VerifiedEmailIdentity("subject", "person@example.com"))
                .value

        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `user API rejects a token with the wrong issuer`() {
        val token =
            SidekickJwtTokens(
                    WebAuthFixture().properties.copy(jwtIssuer = "https://accounts.google.com"),
                    Clock.systemUTC(),
                )
                .issue(VerifiedEmailIdentity("subject", "person@example.com"))
                .value

        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `user API rejects a token with the wrong audience`() {
        val token =
            SidekickJwtTokens(
                    WebAuthFixture().properties.copy(jwtAudience = "another-api"),
                    Clock.systemUTC(),
                )
                .issue(VerifiedEmailIdentity("subject", "person@example.com"))
                .value

        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `bot metadata stays on a separate public endpoint`() {
        mvc.get("/api/web/sidekick/profile").andExpect { status { isOk() } }
    }

    @Test
    fun `bearer CORS preflight does not require authentication`() {
        mvc.options("/api/web/user/profile") {
                header("Origin", "http://localhost:4200")
                header("Access-Control-Request-Method", "GET")
                header("Access-Control-Request-Headers", "Authorization")
            }
            .andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Origin", "http://localhost:4200") }
            }
    }

    @Test
    fun `encore cookie cannot authenticate user or admin APIs`() {
        val result =
            context
                .getBean(SignInUsecase::class.java)
                .execute(
                    SignInUsecase.Command(
                        VerifiedEmailIdentity("google:subject", "person@example.com")
                    )
                ) as SignInUsecase.Result.Success
        val value = context.getBean(BrowserSessions::class.java).issue(result)
        val cookie = Cookie(ENCORE_COOKIE, value)

        mvc.get("/api/web/user/profile") { cookie(cookie) }
            .andExpect { status { isUnauthorized() } }
        mvc.get("/api/web/admin/config") { cookie(cookie) }
            .andExpect { status { isUnauthorized() } }
        mvc.get("/api/web/user/profile") { header("Authorization", "Bearer $value") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `session endpoint allows credentialed browser requests from the configured frontend`() {
        val result =
            context
                .getBean(SignInUsecase::class.java)
                .execute(
                    SignInUsecase.Command(
                        VerifiedEmailIdentity("google:subject", "person@example.com")
                    )
                ) as SignInUsecase.Result.Success
        val value = context.getBean(BrowserSessions::class.java).issue(result)

        mvc.get(BROWSER_SESSION_PATH) {
                cookie(Cookie(ENCORE_COOKIE, value))
                header("Origin", "http://localhost:4200")
                header("X-Sidekick-Session", "1")
            }
            .andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Credentials", "true") }
            }
    }

    @Test
    fun `session logout preflight rejects a foreign origin`() {
        mvc.options(BROWSER_SESSION_PATH) {
                header("Origin", "https://foreign.example")
                header("Access-Control-Request-Method", "DELETE")
                header("Access-Control-Request-Headers", "X-Sidekick-Session")
            }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `session logout preflight allows the configured frontend`() {
        mvc.options(BROWSER_SESSION_PATH) {
                header("Origin", "http://localhost:4200")
                header("Access-Control-Request-Method", "DELETE")
                header("Access-Control-Request-Headers", "X-Sidekick-Session")
            }
            .andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Credentials", "true") }
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(
        WebAuthConfiguration::class,
        UserApiSecurityConfiguration::class,
        WebUiHttpConfiguration::class,
        GoogleSignInController::class,
        BrowserSessionController::class,
        SidekickProfileController::class,
        UserProfileController::class,
    )
    class TestConfiguration {
        @Bean fun agentConfig() = AgentConfig("Juniper", "unused", "unused")

        @Bean fun slackBotProfile() = SlackBotProfile()
    }
}
