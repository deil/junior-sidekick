package com.github.uncomplexco.sidekick

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
class AppConfig {
    @Bean
    fun mainCoroutineScope() = CoroutineScope(SupervisorJob())
}

@SpringBootApplication
@EnableScheduling
class SidekickBot

fun main(args: Array<String>) {
    runApplication<SidekickBot>(*args)
}
