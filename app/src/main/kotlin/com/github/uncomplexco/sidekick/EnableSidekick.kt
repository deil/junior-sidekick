package com.github.uncomplexco.sidekick

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.PropertySource
import org.springframework.scheduling.annotation.EnableScheduling

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(SidekickConfiguration::class)
annotation class EnableSidekick

@Configuration
@ComponentScan(
    basePackages = [
        "com.github.uncomplexco.sidekick.application",
        "com.github.uncomplexco.sidekick.usecases",
        "com.github.uncomplexco.sidekick.adapters.files",
        "com.github.uncomplexco.sidekick.adapters.git",
        "com.github.uncomplexco.sidekick.adapters.http",
        "com.github.uncomplexco.sidekick.adapters.jgit",
        "com.github.uncomplexco.sidekick.adapters.koog",
        "com.github.uncomplexco.sidekick.adapters.mcp",
        "com.github.uncomplexco.sidekick.adapters.sandbox",
        "com.github.uncomplexco.sidekick.adapters.slack",
        "com.github.uncomplexco.sidekick.adapters.spring",
    ],
)
@EnableScheduling
@PropertySource("classpath:META-INF/sidekick-defaults.properties")
internal class SidekickConfiguration {
    @Bean
    fun sidekickCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
