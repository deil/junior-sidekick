package com.github.uncomplexco.sidekick

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean

@SpringBootTest(classes = [SidekickTestApplication::class])
class EnableSidekickTest {
    @Test
    fun contextLoads() {
    }
}

@SpringBootConfiguration
@EnableSidekick
private class SidekickTestApplication {
    @Bean
    fun hostCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
