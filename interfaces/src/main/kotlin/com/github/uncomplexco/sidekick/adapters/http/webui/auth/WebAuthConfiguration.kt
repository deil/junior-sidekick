package com.github.uncomplexco.sidekick.adapters.http.webui.auth

import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(WebAuthProperties::class)
class WebAuthConfiguration {
    @Bean
    fun sidekickJwtTokens(properties: WebAuthProperties) =
        SidekickJwtTokens(properties, Clock.systemUTC())

    @Bean
    fun webSignInUsecase(properties: WebAuthProperties, tokens: SidekickJwtTokens) =
        SignInUsecase(
            AllowedEmailDomainPolicy(properties.allowedEmailDomain),
            tokens,
        )

    @Bean fun browserLoginGrants() = BrowserLoginGrants()

    @Bean fun browserSessions() = BrowserSessions()

    @Bean
    fun browserLoginHandler(
        signIn: SignInUsecase,
        grants: BrowserLoginGrants,
        @Value($$"${webui.allowed-origin:http://localhost:4200}") frontendOrigin: String,
    ) = BrowserLoginHandler(signIn, grants, frontendOrigin)

    @Bean
    fun browserSignInSecurity(
        http: HttpSecurity,
        properties: WebAuthProperties,
        handler: BrowserLoginHandler,
    ): SecurityFilterChain {
        http
            .securityMatcher("/oauth2/authorization/**", "/login/oauth2/**", "/api/web/auth/**")
            .cors {}
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .requestCache { it.requestCache(NullRequestCache()) }
            .securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .csrf { it.ignoringRequestMatchers("/api/web/auth/token", BROWSER_SESSION_PATH) }
        if (properties.enabled) {
            val registration =
                CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(properties.googleClientId)
                    .clientSecret(properties.googleClientSecret)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .build()
            val registrations = InMemoryClientRegistrationRepository(registration)
            http.oauth2Login { oauth ->
                oauth
                    .clientRegistrationRepository(registrations)
                    .authorizedClientRepository(HttpSessionOAuth2AuthorizedClientRepository())
                    .authorizationEndpoint {
                        it.authorizationRequestResolver(
                            BrowserAuthorizationRequestResolver(registrations)
                        )
                    }
                    .successHandler(handler)
                    .failureHandler(handler)
            }
        }
        return http.build()
    }
}
