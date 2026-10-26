package com.github.uncomplexco.sidekick.adapters.http.webui

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebUiHttpConfiguration(
    @Value($$"${webui.allowed-origin:http://localhost:4200}") private val allowedOrigin: String
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/web/**").allowedOrigins(allowedOrigin)
    }
}
