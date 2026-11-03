package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.model.User
import org.springframework.stereotype.Component

data class SlackBotIdentity(val username: String, val userId: String, val teamId: String)

internal fun slackBotDisplayName(user: User): String =
    user.profile?.displayName?.takeIf { it.isNotBlank() }
        ?: user.profile?.realName?.takeIf { it.isNotBlank() }
        ?: user.realName?.takeIf { it.isNotBlank() }
        ?: user.name

/** Identity resolved by the Slack adapter at startup, without exposing Slack credentials. */
@Component
class SlackBotProfile {
    var identity: SlackBotIdentity? = null
}
