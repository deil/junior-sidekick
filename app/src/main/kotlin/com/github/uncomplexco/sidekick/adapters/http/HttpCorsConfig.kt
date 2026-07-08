package com.github.uncomplexco.sidekick.adapters.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@ConditionalOnProperty(name = ["adapters.http.enabled"], havingValue = "true")
class HttpCorsConfig {
    @Bean
    fun httpCorsFilter(): FilterRegistrationBean<OncePerRequestFilter> =
        FilterRegistrationBean<OncePerRequestFilter>(
            object : OncePerRequestFilter() {
                override fun doFilterInternal(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    filterChain: FilterChain,
                ) {
                    val origin = request.getHeader(HttpHeaders.ORIGIN)
                    if (!origin.isNullOrBlank()) {
                        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS")
                        response.setHeader(
                            HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                            request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS) ?: "*",
                        )
                        response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN)
                        response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)
                        response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)
                    }

                    if (!origin.isNullOrBlank() && request.method == HttpMethod.OPTIONS.name()) {
                        response.status = HttpStatus.NO_CONTENT.value()
                        return
                    }

                    filterChain.doFilter(request, response)
                }
            },
        ).also { registration ->
            registration.addUrlPatterns("/api/*")
            registration.order = Ordered.HIGHEST_PRECEDENCE
        }
}
