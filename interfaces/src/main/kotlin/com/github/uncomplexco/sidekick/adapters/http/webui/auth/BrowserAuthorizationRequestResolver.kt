package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import jakarta.servlet.http.HttpServletRequest
import java.io.Serializable
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

private const val LOGIN_ATTRIBUTE_PREFIX = "sidekick.browser-login."

data class PendingBrowserLogin(val challenge: String, val state: String) : Serializable

class BrowserAuthorizationRequestResolver(registrations: ClientRegistrationRepository) :
    OAuth2AuthorizationRequestResolver {
    private val delegate =
        DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization")

    override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
        remember(request, delegate.resolve(request))

    override fun resolve(
        request: HttpServletRequest,
        clientRegistrationId: String,
    ): OAuth2AuthorizationRequest? =
        remember(request, delegate.resolve(request, clientRegistrationId))

    private fun remember(
        request: HttpServletRequest,
        authorization: OAuth2AuthorizationRequest?,
    ): OAuth2AuthorizationRequest? {
        if (authorization == null) return null
        val challenge = request.getParameter("sidekick_code_challenge").orEmpty()
        val state = request.getParameter("sidekick_state").orEmpty()
        if (!LOGIN_VALUE_RE.matches(challenge) || !LOGIN_VALUE_RE.matches(state)) {
            throw OAuth2AuthenticationException(
                OAuth2Error("invalid_request"),
                "A browser login challenge and state are required",
            )
        }
        request
            .getSession(true)
            .setAttribute(
                LOGIN_ATTRIBUTE_PREFIX + authorization.state,
                PendingBrowserLogin(challenge, state),
            )
        return authorization
    }

    companion object {
        fun take(request: HttpServletRequest): PendingBrowserLogin? {
            val session = request.getSession(false) ?: return null
            val key = LOGIN_ATTRIBUTE_PREFIX + request.getParameter("state").orEmpty()
            val login = session.getAttribute(key) as? PendingBrowserLogin
            session.removeAttribute(key)
            return login
        }
    }
}
