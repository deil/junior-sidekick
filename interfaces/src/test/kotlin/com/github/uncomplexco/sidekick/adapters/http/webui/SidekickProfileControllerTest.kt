package com.github.uncomplexco.sidekick.adapters.http.webui

import com.github.uncomplexco.sidekick.adapters.slack.SlackBotIdentity
import com.github.uncomplexco.sidekick.adapters.slack.SlackBotProfile
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SidekickProfileControllerTest {
    @Test
    fun `returns configured Sidekick name and Slack identity`() {
        val slack =
            SlackBotProfile().apply {
                identity = SlackBotIdentity("juniper_helper", "UBOT", "TTEAM")
            }
        val controller =
            SidekickProfileController(AgentConfig("Juniper", "unused", "unused"), slack)
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()

        mvc.get("/api/web/user/profile").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Juniper") }
            jsonPath("$.slack.username") { value("juniper_helper") }
            jsonPath("$.slack.user_id") { value("UBOT") }
            jsonPath("$.slack.team_id") { value("TTEAM") }
        }
    }

    @Test
    fun `returns null Slack identity when Slack is not connected`() {
        val controller =
            SidekickProfileController(AgentConfig("Fern", "unused", "unused"), SlackBotProfile())
        val mvc = MockMvcBuilders.standaloneSetup(controller).build()

        mvc.get("/api/web/user/profile").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Fern") }
            jsonPath("$.slack") { doesNotExist() }
        }
    }
}
