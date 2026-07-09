package com.github.uncomplexco.sidekick.adapters.http

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpBearerTokenAuthFilterTest {
    @Test
    fun `rejects requests without bearer token`() {
        val filter = HttpBearerTokenAuthFilter("secret")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(MockHttpServletRequest("POST", "/api/conversations"), response, chain)

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.status)
        assertFalse(chain.called)
    }

    @Test
    fun `rejects requests with wrong bearer token`() {
        val filter = HttpBearerTokenAuthFilter("secret")
        val request = MockHttpServletRequest("GET", "/api/conversations/session/messages")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer wrong")
        filter.doFilter(request, response, chain)

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.status)
        assertFalse(chain.called)
    }

    @Test
    fun `allows requests with configured bearer token`() {
        val filter = HttpBearerTokenAuthFilter("secret")
        val request = MockHttpServletRequest("POST", "/api/conversations/session/messages")
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret")
        filter.doFilter(request, response, chain)

        assertTrue(chain.called)
    }

    @Test
    fun `requires configured token`() {
        val error = assertThrows<IllegalArgumentException> { HttpBearerTokenAuthFilter(" ") }

        assertEquals("adapters.http.token is required when adapters.http.enabled=true", error.message)
    }
}

private class CapturingFilterChain : FilterChain {
    var called = false

    override fun doFilter(
        request: jakarta.servlet.ServletRequest,
        response: jakarta.servlet.ServletResponse,
    ) {
        called = true
    }
}
