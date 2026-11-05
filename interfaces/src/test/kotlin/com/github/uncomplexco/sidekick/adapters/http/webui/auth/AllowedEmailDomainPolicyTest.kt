package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AllowedEmailDomainPolicyTest {
    @Test
    fun `blank domain denies a verified email`() {
        assertFalse(AllowedEmailDomainPolicy(" ").allows("person@example.com"))
    }

    @Test
    fun `domain comparison ignores case and optional configuration at sign`() {
        assertTrue(AllowedEmailDomainPolicy(" @Example.com ").allows("person@EXAMPLE.COM"))
    }

    @Test
    fun `subdomains are not admitted by the parent domain`() {
        assertFalse(AllowedEmailDomainPolicy("example.com").allows("person@team.example.com"))
    }

    @Test
    fun `suffix lookalike domains are rejected`() {
        assertFalse(AllowedEmailDomainPolicy("example.com").allows("person@notexample.com"))
    }
}
