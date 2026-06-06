package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.slack.api.methods.MethodsClient
import kotlinx.serialization.Serializable

@LLMDescription("Slack reaction tools for the current inbound message")
class SlackReactionTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Add an emoji reaction to the current inbound Slack message. Use sparingly for lightweight acknowledgements. Provide a Slack emoji alias name (for example `thumbsup`, `white_check_mark`, or `thumbsup::skin-tone-6`), not a unicode emoji glyph. The target message is injected by runtime context; do not use this for arbitrary historical messages.",
    )
    fun slackReactionAdd(
        @LLMDescription("Slack emoji alias name. Surrounding colons are optional.")
        emoji: String,
    ): SlackReactionAddedResult {
        val normalizedEmoji = normalizeSlackReactionEmoji(emoji)
        val response =
            slackClient.reactionsAdd { req ->
                req.channel(ctx.conversationId.channelId)
                req.timestamp(ctx.currentMessageId)
                req.name(normalizedEmoji)
            }
        if (!response.isOk) {
            throw IllegalStateException(response.error ?: "Failed to add Slack reaction")
        }

        return SlackReactionAddedResult(
            ok = true,
            channelId = ctx.conversationId.channelId,
            messageTs = ctx.currentMessageId,
            emoji = normalizedEmoji,
        )
    }
}

@Serializable
data class SlackReactionAddedResult(
    val ok: Boolean,
    val channelId: String,
    val messageTs: String,
    val emoji: String,
)

fun normalizeSlackReactionEmoji(emoji: String): String {
    val normalized = emoji.trim().trim(':').lowercase()
    require(normalized.isNotBlank()) { "Slack emoji alias is required." }
    require(normalized.matches(SLACK_REACTION_EMOJI_REGEX)) { "Invalid Slack emoji alias." }
    return normalized
}

private val SLACK_REACTION_EMOJI_REGEX = Regex("^[a-z0-9_+-]+(?:::skin-tone-[2-6])?$")
