package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

internal val WEB_AUTH_LIFETIME: Duration = Duration.ofHours(24)

@ConfigurationProperties("webui.auth")
data class WebAuthProperties(
    val googleClientId: String = "",
    val googleClientSecret: String = "",
    val allowedEmailDomain: String = "",
    val jwtSigningKey: String = "",
    val jwtIssuer: String = "sidekick",
    val jwtAudience: String = "sidekick-api",
) {
    val enabled: Boolean
        get() =
            googleClientId.isNotBlank() &&
                googleClientSecret.isNotBlank() &&
                allowedEmailDomain.trim().removePrefix("@").isNotBlank() &&
                jwtSigningKey.isNotBlank()
}
