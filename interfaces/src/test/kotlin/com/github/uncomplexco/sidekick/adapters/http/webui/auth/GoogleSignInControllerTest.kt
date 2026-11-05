package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.github.uncomplexco.sidekick.testing.WebAuthFixture
import com.jayway.jsonpath.JsonPath
import java.net.URI
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder

class GoogleSignInControllerTest {
    private val fixture = WebAuthFixture()

    private fun code(location: String) =
        checkNotNull(
            UriComponentsBuilder.fromUri(URI(location)).build().queryParams.getFirst("code")
        )

    @Test
    fun `exchanges the OIDC login handoff for a verifiable Sidekick JWT and destroys the login session`() {
        val login = fixture.completeLogin()
        val location = checkNotNull(login.response.redirectedUrl)
        val handoff = code(location)

        val response =
            fixture.mvc
                .post("/api/web/auth/token") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"code":"$handoff","code_verifier":"${fixture.verifier}"}"""
                }
                .andExpect {
                    status { isOk() }
                    header { string("Cache-Control", "no-store") }
                    jsonPath("$.email") { doesNotExist() }
                    jsonPath("$.token_type") { value("Bearer") }
                    jsonPath("$.expires_at") { value(fixture.now.plusSeconds(86400).toString()) }
                }
                .andReturn()
                .response

        val token = JsonPath.read<String>(response.contentAsString, "$.access_token")
        val decoded =
            NimbusJwtDecoder.withSecretKey(SecretKeySpec(fixture.signingBytes, "HmacSHA256"))
                .build()
                .decode(token)
        assertEquals("google:google-test-subject", decoded.subject)
        assertEquals("person@example.com", decoded.getClaimAsString("email"))
        assertEquals("sidekick", decoded.getClaimAsString("iss"))
        assertEquals(listOf("sidekick-api"), decoded.audience)
        assertTrue(login.session.isInvalid)
        assertFalse(location.contains(token))
    }

    @Test
    fun `rejects redemption without the initiating browser verifier`() {
        val handoff = code(checkNotNull(fixture.completeLogin().response.redirectedUrl))

        fixture.mvc
            .post("/api/web/auth/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"$handoff","code_verifier":"${"x".repeat(43)}"}"""
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a redeemed handoff cannot be reused`() {
        val handoff = code(checkNotNull(fixture.completeLogin().response.redirectedUrl))
        checkNotNull(fixture.grants.redeem(handoff, fixture.verifier))

        fixture.mvc
            .post("/api/web/auth/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"$handoff","code_verifier":"${fixture.verifier}"}"""
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a handoff expires after sixty seconds`() {
        val handoff = code(checkNotNull(fixture.completeLogin().response.redirectedUrl))
        fixture.now = fixture.now.plusSeconds(61)

        fixture.mvc
            .post("/api/web/auth/token") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"$handoff","code_verifier":"${fixture.verifier}"}"""
            }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `OIDC login without email produces no token handoff`() {
        val login = fixture.completeLogin(email = null)

        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
        assertTrue(login.session.isInvalid)
    }

    @Test
    fun `OIDC login with unverified email produces no token handoff`() {
        val login = fixture.completeLogin(verified = false)

        assertEquals("https://frontend.test/login?error=identity", login.response.redirectedUrl)
    }

    @Test
    fun `OIDC login from another email domain produces no token handoff`() {
        val login = fixture.completeLogin(email = "person@other.example")

        assertEquals("https://frontend.test/login?error=domain", login.response.redirectedUrl)
    }

    @Test
    fun `blank allowed domain produces no token handoff`() {
        val login = fixture.completeLogin(domain = " ")

        assertEquals("https://frontend.test/login?error=domain", login.response.redirectedUrl)
    }

    @Test
    fun `public configuration contains no Google or Sidekick secret`() {
        fixture.mvc.get("/api/web/auth/config").andExpect {
            status { isOk() }
            content { json("""{"enabled":true}""", JsonCompareMode.STRICT) }
            header { string("Cache-Control", "no-store") }
        }
    }
}
