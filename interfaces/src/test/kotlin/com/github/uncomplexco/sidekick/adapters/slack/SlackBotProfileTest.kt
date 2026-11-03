package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.model.User
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SlackBotProfileTest {
    @Test
    fun `preserves punctuation in the bot's Slack display name`() {
        val user =
            User().apply {
                name = "juniorsidekick"
                profile = User.Profile().apply { displayName = "junior-sidekick" }
            }

        assertEquals("junior-sidekick", slackBotDisplayName(user))
    }

    @Test
    fun `uses the bot profile name when display name is empty instead of the normalized username`() {
        val user =
            User().apply {
                name = "juniorsidekick"
                realName = "junior-sidekick"
                profile =
                    User.Profile().apply {
                        displayName = ""
                        realName = "junior-sidekick"
                    }
            }

        assertEquals("junior-sidekick", slackBotDisplayName(user))
    }

    @Test
    fun `uses username when no display name is set`() {
        val user =
            User().apply {
                name = "juniper"
                profile = User.Profile().apply { displayName = "" }
            }

        assertEquals("juniper", slackBotDisplayName(user))
    }
}
