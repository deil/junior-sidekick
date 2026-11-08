package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import com.github.uncomplexco.sidekick.application.core.User
import com.github.uncomplexco.sidekick.ports.UsersRepository
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory

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
    private val users: UsersRepository,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun execute(command: Command): Result {
        val identity = command.identity
        if (!domains.allows(identity.email)) return Result.DomainNotAllowed

        val idpUser = users.findAllByIdpId(command.idpId).singleOrNull()
        val emailUser = users.findByEmail(identity.email)
        if (idpUser != null && emailUser != null && idpUser.id != emailUser.id) {
            return Result.EmailAlreadyLinked
        }

        val token = tokens.issue(identity)
        val now = clock.instant()

        if (idpUser != null) {
            users.updateEmailByIdpId(command.idpId, identity.email, now)

            logger.info("Updated web identity and recorded login idpId={}", command.idpId)
        } else if (emailUser == null) {
            users.add(
                User(
                    name = command.name,
                    email = identity.email,
                    idpId = command.idpId,
                    createdAt = now,
                    lastSeenAt = now,
                )
            )

            logger.info("Registered web user idpId={}", command.idpId)
        } else {
            users.linkIdpId(identity.email, command.idpId, now)

            logger.info("Linked web identity and recorded login idpId={}", command.idpId)
        }

        return Result.Success(identity, token)
    }

    data class Command(val identity: VerifiedEmailIdentity, val name: String, val idpId: String)

    sealed interface Result {
        data class Success(val identity: VerifiedEmailIdentity, val token: IssuedAccessToken) :
            Result

        data object DomainNotAllowed : Result

        data object EmailAlreadyLinked : Result
    }
}
