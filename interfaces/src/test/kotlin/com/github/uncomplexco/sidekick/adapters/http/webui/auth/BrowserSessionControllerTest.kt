package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.github.uncomplexco.sidekick.testing.WebAuthFixture
import com.jayway.jsonpath.JsonPath
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockCookie
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder

class BrowserSessionControllerTest {
    private val fixture = WebAuthFixture()

    private fun signIn(): MockCookie {
        val location = checkNotNull(fixture.completeLogin().response.redirectedUrl)
        val code = UriComponentsBuilder.fromUri(URI(location)).build().queryParams.getFirst("code")
        val response =
            fixture.mvc
                .post("/api/web/auth/token") {
                    secure = true
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"code":"$code","code_verifier":"${fixture.verifier}"}"""
                }
                .andExpect { status { isOk() } }
                .andReturn()
                .response
        return MockCookie.parse(checkNotNull(response.getHeader("Set-Cookie")))
    }

    @Test
    fun `login sets an HttpOnly secure cookie scoped to session checks for 24 hours`() {
        val cookie = signIn()

        assertEquals("sidekick-encore", cookie.name)
        assertEquals("/api/web/auth/session", cookie.path)
        assertEquals(86400, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.secure)
        assertEquals("None", cookie.sameSite)
        assertEquals(null, cookie.domain)
    }

    @Test
    fun `remembered browser recovers its access token without extending its expiry`() {
        val cookie = signIn()
        val expiresAt = fixture.now.plusSeconds(86400)
        fixture.now = fixture.now.plusSeconds(3600)

        val response =
            fixture.mvc
                .get(BROWSER_SESSION_PATH) {
                    cookie(cookie)
                    header("X-Sidekick-Session", "1")
                }
                .andExpect {
                    status { isOk() }
                    header { string("Cache-Control", "no-store") }
                    header { doesNotExist("Set-Cookie") }
                    jsonPath("$.email") { doesNotExist() }
                    jsonPath("$.expires_at") { value(expiresAt.toString()) }
                }
                .andReturn()
                .response

        val token =
            fixture.tokens
                .decoder()
                .decode(JsonPath.read(response.contentAsString, "$.access_token"))
        assertEquals("google:google-test-subject", token.subject)
        assertEquals("person@example.com", token.getClaimAsString("email"))
    }

    @Test
    fun `restoration does not extend the fixed 24 hour lifetime`() {
        val cookie = signIn()
        fixture.now = fixture.now.plusSeconds(86399)
        fixture.mvc
            .get(BROWSER_SESSION_PATH) {
                cookie(cookie)
                header("X-Sidekick-Session", "1")
            }
            .andExpect { status { isOk() } }
        fixture.now = fixture.now.plusSeconds(1)

        fixture.mvc
            .get(BROWSER_SESSION_PATH) {
                cookie(cookie)
                header("X-Sidekick-Session", "1")
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `session check without a cookie returns unauthorized`() {
        fixture.mvc
            .get(BROWSER_SESSION_PATH) {
                header("X-Sidekick-Session", "1")
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `signing out expires the cookie and rejects replay of the old credential`() {
        val cookie = signIn()

        val response =
            fixture.mvc
                .delete(BROWSER_SESSION_PATH) {
                    secure = true
                    cookie(cookie)
                    header("X-Sidekick-Session", "1")
                }
                .andExpect { status { isOk() } }
                .andReturn()
                .response

        val cleared = MockCookie.parse(checkNotNull(response.getHeader("Set-Cookie")))
        assertEquals(BROWSER_SESSION_PATH, cleared.path)
        assertEquals(0, cleared.maxAge)
        fixture.mvc
            .get(BROWSER_SESSION_PATH) {
                cookie(cookie)
                header("X-Sidekick-Session", "1")
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `session deletion requires a custom browser header`() {
        val cookie = signIn()

        fixture.mvc
            .delete(BROWSER_SESSION_PATH) {
                cookie(cookie)
            }
            .andExpect { status { isBadRequest() } }

        fixture.mvc
            .get(BROWSER_SESSION_PATH) {
                cookie(cookie)
                header("X-Sidekick-Session", "1")
            }
            .andExpect { status { isOk() } }
    }
}
