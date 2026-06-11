package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Conversation
import com.slack.api.model.ConversationType
import kotlinx.serialization.Serializable

private const val DEFAULT_SLACK_CHANNEL_LIMIT = 200
private const val MAX_SLACK_CHANNEL_LIMIT = 1000

@LLMDescription("Slack tools for finding visible channels")
class SlackChannelTools(
    private val slackClient: MethodsClient,
) : ToolSet {
    @Tool
    @LLMDescription(
        "List or search visible Slack channels. If query is blank or omitted, returns channels from the current page. Use nextCursor to continue.",
    )
    fun slackChannelsList(
        @LLMDescription("Optional channel name search text. Matches channel names case-insensitively; leading # is ignored.")
        query: String? = null,
        @LLMDescription("Maximum channels to fetch from Slack before local name filtering. Defaults to 200 and is capped at 1000.")
        limit: Int? = null,
        @LLMDescription("Slack pagination cursor from a previous slackChannels result.")
        cursor: String? = null,
        @LLMDescription("Whether to include archived channels. Defaults to false.")
        includeArchived: Boolean? = null,
    ): SlackChannelsResult {
        val requestedLimit = normalizeSlackChannelLimit(limit)
        val normalizedQuery = normalizeSlackChannelQuery(query)
        val response =
            slackClient.conversationsList { req ->
                req.types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                req.excludeArchived(includeArchived != true)
                req.limit(requestedLimit)
                cursor?.takeIf { it.isNotBlank() }?.let(req::cursor)
                req
            }
        if (!response.isOk) {
            throw ToolException.ValidationFailure(response.error ?: "Failed to list Slack channels.")
        }

        val channels =
            response.channels
                .orEmpty()
                .filter { channel -> normalizedQuery == null || channel.matchesName(normalizedQuery) }
                .map { it.toSlackChannelSummary() }

        return SlackChannelsResult(
            ok = true,
            query = normalizedQuery,
            limit = requestedLimit,
            nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
            channelsReturned = channels.size,
            channels = channels,
        )
    }
}

fun normalizeSlackChannelLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_SLACK_CHANNEL_LIMIT
    if (value < 1) {
        throw ToolException.ValidationFailure("Slack channel limit must be greater than or equal to 1.")
    }
    return minOf(value, MAX_SLACK_CHANNEL_LIMIT)
}

fun normalizeSlackChannelQuery(query: String?): String? =
    query
        ?.trim()
        ?.trimStart('#')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

private fun Conversation.matchesName(query: String): Boolean = listOfNotNull(nameNormalized, name).any { it.lowercase().contains(query) }

private fun Conversation.toSlackChannelSummary(): SlackChannelSummary =
    SlackChannelSummary(
        id = id,
        name = nameNormalized ?: name.orEmpty(),
        createdAt = slackTsToUtc(created),
        isArchived = isArchived,
        isPrivate = isPrivate,
        isMember = isMember,
        isShared = isShared,
        topic = topic?.value,
        purpose = purpose?.value,
        memberCount = numOfMembers,
    )

@Serializable
data class SlackChannelsResult(
    val ok: Boolean,
    val query: String?,
    val limit: Int,
    val nextCursor: String?,
    val channelsReturned: Int,
    val channels: List<SlackChannelSummary>,
)

@Serializable
data class SlackChannelSummary(
    val id: String,
    val name: String,
    @LLMDescription("Slack channel creation time in ISO UTC")
    val createdAt: String,
    val isArchived: Boolean,
    val isPrivate: Boolean,
    val isMember: Boolean,
    val isShared: Boolean,
    val topic: String?,
    val purpose: String?,
    val memberCount: Int,
)
