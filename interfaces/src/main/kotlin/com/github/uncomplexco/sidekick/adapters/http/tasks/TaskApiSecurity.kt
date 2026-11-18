package com.github.uncomplexco.sidekick.adapters.http.tasks

import com.github.uncomplexco.sidekick.adapters.http.tasks.TaskApiController.Companion.PATH
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.savedrequest.NullRequestCache

@Configuration
@EnableConfigurationProperties(TaskApiProperties::class)
class TaskApiSecurity {
    @Bean
    @Order(0)
    fun taskApiSecurityFilterChain(
        http: HttpSecurity,
        properties: TaskApiProperties,
    ): SecurityFilterChain {
        val keys = TaskApiKeys(properties)
        return http
            .securityMatcher(PATH, "$PATH/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.securityContextRepository(NullSecurityContextRepository()) }
            .requestCache { it.requestCache(NullRequestCache()) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .oauth2ResourceServer {
                it.opaqueToken { token -> token.introspector(keys) }
            }
            .build()
    }
}
