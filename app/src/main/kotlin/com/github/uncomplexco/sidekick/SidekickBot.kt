package com.github.uncomplexco.sidekick

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {
    @Bean
    fun mainCoroutineScope() = CoroutineScope(SupervisorJob())
}

@SpringBootApplication
class SidekickBot

fun main(args: Array<String>) {
    runApplication<SidekickBot>(*args)
}
