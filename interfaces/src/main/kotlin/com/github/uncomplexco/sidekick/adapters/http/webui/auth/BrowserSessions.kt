package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal const val ENCORE_COOKIE = "sidekick-encore"
internal const val BROWSER_SESSION_PATH = "/api/web/auth/session"

/** Browser continuity only. These credentials never authenticate user API requests. */
class BrowserSessions(private val clock: Clock = Clock.systemUTC()) {
    private val sessions = ConcurrentHashMap<String, SignInUsecase.Result.Success>()
    private val random = SecureRandom()

    fun issue(result: SignInUsecase.Result.Success): String {
        val now = clock.instant()
        sessions.entries.removeIf { !it.value.token.expiresAt.isAfter(now) }
        val credential =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(random::nextBytes))
        sessions[credential] = result
        return credential
    }

    fun find(credential: String?): SignInUsecase.Result.Success? {
        val session = credential?.let(sessions::get) ?: return null
        if (!session.token.expiresAt.isAfter(clock.instant())) {
            sessions.remove(credential)
            return null
        }
        return session
    }

    fun remainingSeconds(result: SignInUsecase.Result.Success): Long =
        Duration.between(clock.instant(), result.token.expiresAt).seconds.coerceAtLeast(0)

    fun revoke(credential: String?) {
        if (credential != null) sessions.remove(credential)
    }
}
