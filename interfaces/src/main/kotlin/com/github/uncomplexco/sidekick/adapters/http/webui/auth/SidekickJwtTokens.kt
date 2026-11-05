package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import java.time.Clock
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

class SidekickJwtTokens(
    private val properties: WebAuthProperties,
    private val clock: Clock,
) : AccessTokenIssuer {
    private val key =
        properties.jwtSigningKey
            .takeIf { it.isNotBlank() }
            ?.let {
                val bytes = Base64.getDecoder().decode(it)
                require(bytes.size >= 32) {
                    "webui.auth.jwt-signing-key must be Base64-encoded with at least 32 random bytes"
                }
                SecretKeySpec(bytes, "HmacSHA256")
            }
    private val encoder = key?.let { NimbusJwtEncoder(ImmutableSecret<SecurityContext>(it)) }

    init {
        require(properties.jwtIssuer.isNotBlank() && properties.jwtAudience.isNotBlank()) {
            "JWT issuer and audience must not be blank"
        }
    }

    override fun issue(identity: VerifiedEmailIdentity): IssuedAccessToken {
        val signer = checkNotNull(encoder) { "JWT signing is not configured" }
        val now = clock.instant()
        val expiresAt = now.plus(WEB_AUTH_LIFETIME)
        val claims =
            JwtClaimsSet.builder()
                .issuer(properties.jwtIssuer)
                .audience(listOf(properties.jwtAudience))
                .subject(identity.subject)
                .claim("email", identity.email)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build()

        return IssuedAccessToken(
            signer.encode(JwtEncoderParameters.from(header, claims)).tokenValue,
            expiresAt,
        )
    }

    fun decoder(): JwtDecoder {
        val signingKey =
            key ?: return JwtDecoder { throw BadJwtException("JWT signing is not configured") }
        val decoder =
            NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.jwtIssuer),
                OAuth2TokenValidator<Jwt> {
                    if (
                        it.audience?.contains(properties.jwtAudience) == true &&
                            it.expiresAt != null
                    ) {
                        OAuth2TokenValidatorResult.success()
                    } else {
                        OAuth2TokenValidatorResult.failure(
                            OAuth2Error(
                                "invalid_token",
                                "Required audience or expiry is missing or invalid",
                                null,
                            )
                        )
                    }
                },
                JwtClaimValidator<String>("email") { !it.isNullOrBlank() },
                JwtClaimValidator<String>("sub") { !it.isNullOrBlank() },
            )
        )
        return decoder
    }
}
