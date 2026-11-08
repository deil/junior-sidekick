package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.util.UriComponentsBuilder

class BrowserLoginHandler(
    private val signIn: SignInUsecase,
    private val grants: BrowserLoginGrants,
    private val frontendOrigin: String,
) : AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        try {
            val pending = BrowserAuthorizationRequestResolver.take(request)
            val oidc = authentication.principal as? OidcUser
            val provider =
                (authentication as? OAuth2AuthenticationToken)?.authorizedClientRegistrationId
            val email = oidc?.email
            val subject = oidc?.subject
            if (
                pending == null ||
                    provider == null ||
                    email.isNullOrBlank() ||
                    subject.isNullOrBlank() ||
                    oidc.emailVerified != true
            ) {
                logger.info(
                    "Browser sign-in rejected: verified email and login transaction are required"
                )
                response.sendRedirect(errorUrl("identity"))
                return
            }
            when (
                val result =
                    signIn.execute(
                        SignInUsecase.Command(
                            identity = VerifiedEmailIdentity("$provider:$subject", email),
                            name = oidc.fullName?.takeIf { it.isNotBlank() } ?: email,
                            idpId = subject,
                        )
                    )
            ) {
                is SignInUsecase.Result.Success -> {
                    val code = grants.issue(pending.challenge, result)
                    logger.info(
                        "Google sign-in completed for email={}; browser token handoff created",
                        result.identity.email,
                    )
                    response.setHeader("Cache-Control", "no-store")
                    response.sendRedirect(
                        UriComponentsBuilder.fromUriString(frontendOrigin)
                            .path("/login")
                            .queryParam("code", code)
                            .queryParam("state", pending.state)
                            .build()
                            .encode()
                            .toUriString()
                    )
                }
                SignInUsecase.Result.DomainNotAllowed -> {
                    logger.info("Browser sign-in rejected: email domain is not allowed")
                    response.sendRedirect(errorUrl("domain"))
                }
                SignInUsecase.Result.EmailAlreadyLinked -> {
                    logger.info("Browser sign-in rejected: email belongs to another user")
                    response.sendRedirect(errorUrl("identity"))
                }
            }
        } finally {
            request.getSession(false)?.invalidate()
            SecurityContextHolder.clearContext()
        }
    }

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        logger.info("Google sign-in failed")
        response.sendRedirect(errorUrl("google"))
    }

    private fun errorUrl(error: String) =
        UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/login")
            .queryParam("error", error)
            .build()
            .encode()
            .toUriString()
}
