package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import java.time.Instant

data class VerifiedEmailIdentity(val subject: String, val email: String)

data class IssuedAccessToken(val value: String, val expiresAt: Instant)

fun interface AccessTokenIssuer {
    fun issue(identity: VerifiedEmailIdentity): IssuedAccessToken
}

class AllowedEmailDomainPolicy(allowedDomain: String) {
    private val domain = allowedDomain.trim().removePrefix("@")

    fun allows(email: String): Boolean {
        if (domain.isBlank()) return false
        val separator = email.indexOf('@')
        if (separator <= 0 || separator != email.lastIndexOf('@')) return false
        return email.substring(separator + 1).equals(domain, ignoreCase = true)
    }
}

class SignInUsecase(
    private val domains: AllowedEmailDomainPolicy,
    private val tokens: AccessTokenIssuer,
) {
    fun execute(command: Command): Result {
        val identity = command.identity
        if (!domains.allows(identity.email)) return Result.DomainNotAllowed

        return Result.Success(identity, tokens.issue(identity))
    }

    data class Command(val identity: VerifiedEmailIdentity)

    sealed interface Result {
        data class Success(val identity: VerifiedEmailIdentity, val token: IssuedAccessToken) :
            Result

        data object DomainNotAllowed : Result
    }
}
