package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class BrowserTokenRequest(
    val code: String,
    @get:JsonProperty("code_verifier") val codeVerifier: String,
)

data class WebAuthConfig(val enabled: Boolean)

data class WebAccessToken(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("token_type") val tokenType: String = "Bearer",
    @get:JsonProperty("expires_at")
    @get:JsonFormat(shape = JsonFormat.Shape.STRING)
    val expiresAt: Instant,
)

@RestController
@RequestMapping("/api/web/auth")
class GoogleSignInController(
    private val properties: WebAuthProperties,
    private val grants: BrowserLoginGrants,
    private val sessions: BrowserSessions,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/config")
    fun config(): ResponseEntity<WebAuthConfig> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(WebAuthConfig(properties.enabled))

    @PostMapping("/token", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun exchange(
        @RequestBody request: BrowserTokenRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<WebAccessToken> {
        if (properties.allowedEmailDomain.trim().removePrefix("@").isBlank()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Sign-in is not allowed")
        }
        if (!properties.enabled)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Sign-in is not configured",
            )
        val result =
            grants.redeem(request.code, request.codeVerifier)
                ?: run {
                    logger.info(
                        "Browser token exchange rejected: invalid, expired, or consumed handoff"
                    )
                    throw ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Sign-in handoff is invalid or expired",
                    )
                }
        val credential = sessions.issue(result)
        logger.info(
            "Sidekick access token and browser session delivered for email={}",
            result.identity.email,
        )
        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                encoreCookie(credential, sessions.remainingSeconds(result), servletRequest)
                    .toString(),
            )
            .cacheControl(CacheControl.noStore())
            .body(
                WebAccessToken(
                    result.token.value,
                    expiresAt = result.token.expiresAt,
                )
            )
    }
}

internal fun encoreCookie(
    value: String,
    maxAge: Long,
    request: HttpServletRequest,
): ResponseCookie =
    ResponseCookie.from(ENCORE_COOKIE, value)
        .httpOnly(true)
        .secure(request.isSecure)
        .sameSite(if (request.isSecure) "None" else "Lax")
        .path(BROWSER_SESSION_PATH)
        .maxAge(maxAge)
        .build()
