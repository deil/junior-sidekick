package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val VERIFIER_RE = Regex("^[A-Za-z0-9._~-]{43,128}$")
internal val LOGIN_VALUE_RE = Regex("^[A-Za-z0-9_-]{43}$")

/** Single-use, 60-second handoff from the Google callback to the initiating browser. */
class BrowserLoginGrants(private val clock: Clock = Clock.systemUTC()) {
    private data class Grant(
        val challenge: String,
        val result: SignInUsecase.Result.Success,
        val expiresAt: Instant,
    )

    private val grants = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()

    fun issue(challenge: String, result: SignInUsecase.Result.Success): String {
        val now = clock.instant()
        grants.entries.removeIf { !it.value.expiresAt.isAfter(now) }
        val code =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(random::nextBytes))
        grants[code] = Grant(challenge, result, now.plusSeconds(60))
        return code
    }

    fun redeem(code: String, verifier: String): SignInUsecase.Result.Success? {
        if (!VERIFIER_RE.matches(verifier)) return null
        val grant = grants.remove(code) ?: return null
        if (!grant.expiresAt.isAfter(clock.instant())) return null
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        if (
            !MessageDigest.isEqual(
                challenge.toByteArray(StandardCharsets.US_ASCII),
                grant.challenge.toByteArray(StandardCharsets.US_ASCII),
            )
        )
            return null
        return grant.result
    }
}
