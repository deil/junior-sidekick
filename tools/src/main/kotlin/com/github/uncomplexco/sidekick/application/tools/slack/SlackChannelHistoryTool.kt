package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.slack.api.methods.MethodsClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_CHANNEL_HISTORY_LIMIT = 100
private const val MAX_CHANNEL_HISTORY_LIMIT = 200

@LLMDescription("Slack tools for reading channel history visible to Sidekick")
class SlackChannelHistoryTool(
    private val slackClient: MethodsClient,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Reads Slack channel history. Use nextCursor from the result as cursor to continue reading more history.",
    )
    fun slackChannelHistory(
        @LLMDescription("Slack channel id like C123 or G123. Channel names are accepted only if Slack API accepts them.")
        channel: String,
        @LLMDescription("Maximum number of top-level messages to return. Defaults to 100 and is capped at 200.")
        limit: Int? = null,
        @LLMDescription("Slack pagination cursor from a previous slackChannelHistory result.")
        cursor: String? = null,
        @LLMDescription("Oldest Slack timestamp to include, e.g. 1717431123.000000.")
        oldest: String? = null,
        @LLMDescription("Latest Slack timestamp to include, e.g. 1717517523.000000.")
        latest: String? = null,
    ): SlackChannelHistoryResult {
        val requestedLimit = normalizeLimit(limit)
        val normalizedChannel = channel.trim().trimStart('#')
        if (normalizedChannel.isBlank()) {
            throw ToolException.ValidationFailure("Slack channel is required.")
        }

        val info =
            slackClient.conversationsInfo { req ->
                req.channel(normalizedChannel)
            }
        if (!info.isOk) {
            throw ToolException.ValidationFailure(info.error ?: "Failed to read Slack channel info.")
        }

        val slackChannel = info.channel ?: throw ToolException.ValidationFailure("Slack channel not found.")
        val channelId = slackChannel.id ?: normalizedChannel
        val history =
            slackClient.conversationsHistory { req ->
                req.channel(channelId)
                req.limit(requestedLimit)
                cursor?.takeIf { it.isNotBlank() }?.let(req::cursor)
                oldest?.takeIf { it.isNotBlank() }?.let(req::oldest)
                latest?.takeIf { it.isNotBlank() }?.let(req::latest)
                req
            }
        if (!history.isOk) {
            throw ToolException.ValidationFailure(history.error ?: "Failed to read Slack channel history.")
        }

        val messages =
            history.messages.orEmpty().map { message ->
                SlackChannelHistoryMessage(
                    id = message.ts,
                    sentAt = slackMessageTsToUtc(message.ts),
                    user = message.user,
                    username = message.username,
                    botId = message.botId,
                    subtype = message.subtype,
                    text = message.text,
                    isThread = (message.replyCount ?: 0) > 0,
                    replyCount = message.replyCount ?: 0,
                    threadTs = message.threadTs,
                )
            }

        return SlackChannelHistoryResult(
            ok = true,
            channelId = slackChannel.id,
            channelName = slackChannel.nameNormalized,
            createdAt = slackTsToUtc(slackChannel.created),
            isArchived = slackChannel.isArchived,
            topic = slackChannel.topic.value,
            oldest = oldest,
            latest = latest,
            limit = requestedLimit,
            nextCursor = history.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
            messagesReturned = messages.size,
            messages = messages,
        )
    }
}

fun normalizeLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_CHANNEL_HISTORY_LIMIT
    if (value < 1) {
        throw ToolException.ValidationFailure("Slack channel history limit must be greater than or equal to 1.")
    }
    return minOf(value, MAX_CHANNEL_HISTORY_LIMIT)
}

@Serializable
data class SlackChannelHistoryResult(
    val ok: Boolean,
    val channelId: String,
    val channelName: String,
    @LLMDescription("Slack channel creation time in ISO UTC")
    val createdAt: String,
    val isArchived: Boolean,
    val topic: String?,
    val oldest: String?,
    val latest: String?,
    val limit: Int,
    val nextCursor: String?,
    val messagesReturned: Int,
    val messages: List<SlackChannelHistoryMessage>,
)

@Serializable
data class SlackChannelHistoryMessage(
    val id: String,
    @LLMDescription("Slack message sent time in ISO UTC")
    val sentAt: String,
    val user: String?,
    val username: String?,
    val botId: String?,
    val subtype: String?,
    val text: String?,
    @SerialName("is_thread")
    val isThread: Boolean,
    val replyCount: Int,
    val threadTs: String?,
)
