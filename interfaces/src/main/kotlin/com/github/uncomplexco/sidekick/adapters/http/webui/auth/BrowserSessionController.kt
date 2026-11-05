package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class BrowserSignOut(val signedOut: Boolean = true)

@RestController
@RequestMapping(BROWSER_SESSION_PATH)
class BrowserSessionController(
    private val sessions: BrowserSessions,
    private val properties: WebAuthProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // A custom header requires a CORS preflight. Cross-origin forms cannot call these methods.
    @GetMapping
    fun restore(
        @CookieValue(name = ENCORE_COOKIE, required = false) credential: String?,
        @RequestHeader("X-Sidekick-Session") browserRequest: String,
    ): ResponseEntity<WebAccessToken> {
        val result = sessions.find(credential)
        if (!properties.enabled || result == null) {
            logger.info("Browser session restoration rejected")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        if (
            !AllowedEmailDomainPolicy(properties.allowedEmailDomain).allows(result.identity.email)
        ) {
            sessions.revoke(credential)
            logger.info("Browser session restoration rejected by email domain policy")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
        logger.info("Browser session restored for email={}", result.identity.email)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(WebAccessToken(result.token.value, expiresAt = result.token.expiresAt))
    }

    @DeleteMapping
    fun signOut(
        @CookieValue(name = ENCORE_COOKIE, required = false) credential: String?,
        @RequestHeader("X-Sidekick-Session") browserRequest: String,
        request: HttpServletRequest,
    ): ResponseEntity<BrowserSignOut> {
        sessions.revoke(credential)
        logger.info("Browser session signed out")
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, encoreCookie("", 0, request).toString())
            .body(BrowserSignOut())
    }
}
