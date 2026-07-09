package com.github.uncomplexco.sidekick

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(properties = ["agent.cli-runner.enabled=false"])
class SidekickBotTests {
    @Test
    fun contextLoads() {
    }
}
