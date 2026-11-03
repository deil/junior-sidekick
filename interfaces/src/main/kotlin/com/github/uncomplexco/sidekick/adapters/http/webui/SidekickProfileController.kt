package com.github.uncomplexco.sidekick.adapters.http.webui

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.adapters.slack.SlackBotProfile
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WebSlackProfile(
    val username: String,
    @get:JsonProperty("user_id") val userId: String,
    @get:JsonProperty("team_id") val teamId: String,
)

data class WebAgentProfile(val name: String, val slack: WebSlackProfile?)

@RestController
@RequestMapping("/api/web/user")
class SidekickProfileController(
    private val agentConfig: AgentConfig,
    private val slackBotProfile: SlackBotProfile,
) {
    @GetMapping("/profile")
    fun profile(): WebAgentProfile {
        val slack = slackBotProfile.identity
        return WebAgentProfile(
            name = agentConfig.name,
            slack = slack?.let { WebSlackProfile(it.username, it.userId, it.teamId) },
        )
    }
}
