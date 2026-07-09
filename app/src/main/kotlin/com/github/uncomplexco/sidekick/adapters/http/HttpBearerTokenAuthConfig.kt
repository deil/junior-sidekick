package com.github.uncomplexco.sidekick.adapters.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@ConditionalOnProperty(name = ["adapters.http.enabled"], havingValue = "true")
class HttpBearerTokenAuthConfig(
    @Value($$"${adapters.http.token}") private val token: String,
) {
    @Bean
    fun httpBearerTokenAuthFilter(): FilterRegistrationBean<HttpBearerTokenAuthFilter> =
        FilterRegistrationBean(HttpBearerTokenAuthFilter(token)).also { registration ->
            registration.addUrlPatterns("/api/conversations", "/api/conversations/*")
            registration.order = Ordered.HIGHEST_PRECEDENCE + 1
        }
}

class HttpBearerTokenAuthFilter(
    token: String,
) : OncePerRequestFilter() {
    private val expectedAuthorization = "Bearer ${token.trim()}"

    init {
        require(token.isNotBlank()) { "adapters.http.token is required when adapters.http.enabled=true" }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != expectedAuthorization) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            return
        }

        filterChain.doFilter(request, response)
    }
}
