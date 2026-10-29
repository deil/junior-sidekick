package com.github.uncomplexco.sidekick

import com.github.uncomplexco.sidekick.adapters.http.webui.SidekickProfileController
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(classes = [SidekickTestApplication::class])
class EnableSidekickTest {
    @Autowired lateinit var selectedChatPlatform: ChatPlatform
    @Autowired lateinit var sidekickProfileController: SidekickProfileController
    @Autowired lateinit var webContext: WebApplicationContext

    @Test
    fun contextLoads() {
        kotlin.test.assertEquals(ChatPlatform.SLACK, selectedChatPlatform)
    }

    @Test
    fun `web UI profile allows direct requests from the configured UI origin`() {
        val mvc = MockMvcBuilders.webAppContextSetup(webContext).build()

        mvc.get("/api/web/user/profile") { header("Origin", "http://localhost:4200") }
            .andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Origin", "http://localhost:4200") }
                jsonPath("$.name") { isString() }
            }
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableSidekick
private class SidekickTestApplication {
    @Bean fun hostCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob())
}
