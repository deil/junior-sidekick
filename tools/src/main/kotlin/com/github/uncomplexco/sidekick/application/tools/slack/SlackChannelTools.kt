package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Conversation
import com.slack.api.model.ConversationType

private const val DEFAULT_SLACK_CHANNEL_LIMIT = 200
private const val MAX_SLACK_CHANNEL_LIMIT = 1000

@LLMDescription("Slack tools for finding visible channels")
class SlackChannelTools(
    private val slackClient: MethodsClient,
) : ToolSet {
    @Tool
    @LLMDescription(
        "List or search visible Slack channels. Results are paginated; when hasMore is true, call again with nextCursor to continue listing or searching.",
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
    ): String {
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

        val nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
        return formatSlackChannels(
            channels = channels,
            query = normalizedQuery,
            limit = requestedLimit,
            nextCursor = nextCursor,
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

fun slackChannelContinuationHint(
    query: String?,
    nextCursor: String?,
): String? =
    nextCursor?.let { cursor ->
        if (query == null) {
            "More channels are available. Call slackChannelsList with cursor=$cursor to continue."
        } else {
            "Search is page-based and may be incomplete. Call slackChannelsList with query=$query and cursor=$cursor to continue searching."
        }
    }

fun formatSlackChannels(
    channels: List<Conversation>,
    query: String?,
    limit: Int,
    nextCursor: String?,
): String {
    val output = mutableListOf<String>()
    output += "<slackChannels>"
    output += "<query>${query ?: ""}</query>"
    output += "<limit>$limit</limit>"
    output += "<channels>"
    if (channels.isEmpty()) {
        output += "No matching channels found in this page."
    } else {
        channels.forEachIndexed { index, channel ->
            output += "${index + 1}. ${channel.toSlackChannelLine()}"
        }
    }
    output += "</channels>"
    output += ""
    output += "Showing ${channels.size} matching channel(s) from one Slack page of up to $limit channel(s)."
    slackChannelContinuationHint(query, nextCursor)?.let { output += it }
    if (nextCursor == null) {
        output += "End of Slack channel pages for this request."
    }
    output += "</slackChannels>"
    return output.joinToString("\n")
}

private fun Conversation.toSlackChannelLine(): String =
    listOfNotNull(
        "#${nameNormalized ?: name.orEmpty()}",
        "id=$id",
        "created=${slackTsToUtc(created)}",
        "private=$isPrivate",
        "archived=$isArchived",
        "member=$isMember",
        "shared=$isShared",
        "members=$numOfMembers",
        topic?.value?.takeIf { it.isNotBlank() }?.let { "topic=${it.singleLine()}" },
        purpose?.value?.takeIf { it.isNotBlank() }?.let { "purpose=${it.singleLine()}" },
    ).joinToString(" | ")

private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()
