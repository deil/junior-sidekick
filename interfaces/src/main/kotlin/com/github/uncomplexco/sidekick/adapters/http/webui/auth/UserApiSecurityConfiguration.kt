package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache

@Configuration
class UserApiSecurityConfiguration {
    @Bean
    @Order(1)
    fun userApiSecurity(http: HttpSecurity, tokens: SidekickJwtTokens): SecurityFilterChain {
        http
            .securityMatcher("/api/web/user/**", "/api/web/admin/**")
            .cors {}
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
            .requestCache { it.requestCache(NullRequestCache()) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.decoder(tokens.decoder()) } }
        return http.build()
    }
}
