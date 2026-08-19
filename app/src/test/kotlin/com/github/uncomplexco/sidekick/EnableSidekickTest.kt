package com.github.uncomplexco.sidekick

import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(classes = [SidekickTestApplication::class])
class EnableSidekickTest {
    @Test
    fun contextLoads() {
    }
}

@SpringBootConfiguration
@EnableSidekick
private class SidekickTestApplication
