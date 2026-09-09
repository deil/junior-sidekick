package com.github.uncomplexco.sidekick

import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean

@SpringBootTest(classes = [SidekickTestApplication::class])
class EnableSidekickTest {
    @Autowired
    lateinit var selectedChatPlatform: ChatPlatform

    @Test
    fun contextLoads() {
        kotlin.test.assertEquals(ChatPlatform.SLACK, selectedChatPlatform)
    }
}

@SpringBootConfiguration
@EnableSidekick
private class SidekickTestApplication {
    @Bean
    fun hostCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
