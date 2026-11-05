package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.http.webui.auth.AllowedEmailDomainPolicy
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.BrowserAuthorizationRequestResolver
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.BrowserLoginGrants
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.BrowserLoginHandler
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.BrowserSessionController
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.BrowserSessions
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.GoogleSignInController
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.SidekickJwtTokens
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.SignInUsecase
import com.github.uncomplexco.sidekick.adapters.http.webui.auth.WebAuthProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class WebAuthFixture {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    val state = "s".repeat(43)
    var now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val clock =
        object : Clock() {
            override fun instant() = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this
        }
    val signingBytes = ByteArray(32) { 42 }
    val properties =
        WebAuthProperties(
            googleClientId = "test-client.apps.googleusercontent.com",
            googleClientSecret = "test-only-client-secret",
            allowedEmailDomain = "example.com",
            jwtSigningKey = Base64.getEncoder().encodeToString(signingBytes),
        )
    val grants = BrowserLoginGrants(clock)
    val sessions = BrowserSessions(clock)
    val tokens = SidekickJwtTokens(properties, clock)
    val mvc =
        MockMvcBuilders.standaloneSetup(
                GoogleSignInController(properties, grants, sessions),
                BrowserSessionController(sessions, properties),
            )
            .build()

    data class LoginResult(val response: MockHttpServletResponse, val session: MockHttpSession)

    /** The Google boundary is an OIDC principal already authenticated by Spring Security. */
    fun completeLogin(
        email: String? = "person@example.com",
        verified: Boolean = true,
        domain: String = "example.com",
    ): LoginResult {
        val config = properties.copy(allowedEmailDomain = domain)
        val registration =
            CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(config.googleClientId)
                .clientSecret(config.googleClientSecret)
                .build()
        val resolver =
            BrowserAuthorizationRequestResolver(InMemoryClientRegistrationRepository(registration))
        val start =
            MockHttpServletRequest("GET", "/oauth2/authorization/google").apply {
                servletPath = "/oauth2/authorization/google"
                addParameter("sidekick_code_challenge", challenge)
                addParameter("sidekick_state", state)
            }
        val authorization = checkNotNull(resolver.resolve(start))
        val session = start.getSession(false) as MockHttpSession
        val callback =
            MockHttpServletRequest("GET", "/login/oauth2/code/google").apply {
                setSession(session)
                addParameter("state", authorization.state)
            }
        val claims =
            buildMap<String, Any> {
                put("sub", "google-test-subject")
                put("email_verified", verified)
                put("iss", "https://accounts.google.com")
                put("aud", listOf(config.googleClientId))
                if (email != null) put("email", email)
            }
        val idToken =
            OidcIdToken(
                "framework-verified-token",
                now.minusSeconds(10),
                now.plusSeconds(3600),
                claims,
            )
        val user = DefaultOidcUser(listOf(SimpleGrantedAuthority("OIDC_USER")), idToken)
        val authentication = OAuth2AuthenticationToken(user, user.authorities, "google")
        val handler =
            BrowserLoginHandler(
                SignInUsecase(
                    AllowedEmailDomainPolicy(domain),
                    SidekickJwtTokens(config, clock),
                ),
                grants,
                "https://frontend.test",
            )
        val response = MockHttpServletResponse()

        handler.onAuthenticationSuccess(callback, response, authentication)
        return LoginResult(response, session)
    }
}
