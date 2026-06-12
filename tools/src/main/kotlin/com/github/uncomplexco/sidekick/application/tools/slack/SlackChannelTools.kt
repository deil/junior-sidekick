package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.response.conversations.ConversationsListResponse
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
        "List or search visible Slack channels. Scans Slack pages internally until enough matching channels are found or Slack has no more pages.",
    )
    fun slackChannelsList(
        @LLMDescription("Optional channel name search text. Matches channel names case-insensitively; leading # is ignored.")
        query: String? = null,
        @LLMDescription("Maximum matching channels to return. Defaults to 200 and is capped at 1000.")
        limit: Int? = null,
        @LLMDescription("Slack pagination cursor from a previous slackChannelsList result.")
        cursor: String? = null,
        @LLMDescription("Whether to include archived channels. Defaults to false.")
        includeArchived: Boolean? = null,
    ): String {
        val requestedLimit = normalizeSlackChannelLimit(limit)
        val normalizedQuery = normalizeSlackChannelQuery(query)
        val result =
            collectSlackChannels(
                query = normalizedQuery,
                limit = requestedLimit,
                cursor = cursor,
            ) { pageCursor, pageLimit ->
                slackClient.fetchSlackChannelPage(pageCursor, pageLimit, includeArchived == true)
            }
        return formatSlackChannels(
            channels = result.channels,
            query = normalizedQuery,
            limit = requestedLimit,
            nextCursor = result.nextCursor,
            pagesScanned = result.pagesScanned,
            channelsScanned = result.channelsScanned,
        )
    }
}

data class SlackChannelPage(
    val channels: List<Conversation>,
    val nextCursor: String?,
)

data class SlackChannelScanResult(
    val channels: List<Conversation>,
    val nextCursor: String?,
    val pagesScanned: Int,
    val channelsScanned: Int,
)

fun collectSlackChannels(
    query: String?,
    limit: Int,
    cursor: String?,
    fetchPage: (cursor: String?, limit: Int) -> SlackChannelPage,
): SlackChannelScanResult {
    val channels = mutableListOf<Conversation>()
    var nextCursor = cursor?.takeIf { it.isNotBlank() }
    var pagesScanned = 0
    var channelsScanned = 0

    do {
        val page = fetchPage(nextCursor, slackChannelPageLimit(query, limit - channels.size))
        pagesScanned += 1
        channelsScanned += page.channels.size
        channels += page.channels.filter { channel -> query == null || channel.matchesName(query) }.take(limit - channels.size)
        nextCursor = page.nextCursor
    } while (channels.size < limit && nextCursor != null)

    return SlackChannelScanResult(
        channels = channels,
        nextCursor = nextCursor,
        pagesScanned = pagesScanned,
        channelsScanned = channelsScanned,
    )
}

fun normalizeSlackChannelLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_SLACK_CHANNEL_LIMIT
    if (value < 1) {
        throw ToolException.ValidationFailure("Slack channel limit must be greater than or equal to 1.")
    }
    return minOf(value, MAX_SLACK_CHANNEL_LIMIT)
}

private fun slackChannelPageLimit(
    query: String?,
    remaining: Int,
): Int = if (query == null) remaining.coerceIn(1, MAX_SLACK_CHANNEL_LIMIT) else MAX_SLACK_CHANNEL_LIMIT

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
    pagesScanned: Int,
    channelsScanned: Int,
): String {
    val output = mutableListOf<String>()
    output += "<slackChannels>"
    output += "<query>${query ?: ""}</query>"
    output += "<limit>$limit</limit>"
    output += "<channels>"
    if (channels.isEmpty()) {
        output += "No matching channels found after scanning $pagesScanned Slack page(s)."
    } else {
        channels.forEach { channel ->
            output += channel.toSlackChannelTag()
        }
    }
    output += "</channels>"
    output += ""
    output += "Returned ${channels.size} matching channel(s); requested limit was $limit."
    output += "Scanned $channelsScanned Slack channel(s) across $pagesScanned page(s)."
    slackChannelContinuationHint(query, nextCursor)?.let { output += it }
    if (nextCursor == null) {
        output += "End of Slack channel pages for this request."
    }
    output += "</slackChannels>"
    return output.joinToString("\n")
}

private fun Conversation.toSlackChannelTag(): String =
    listOfNotNull(
        "<channel id=\"${xmlEscape(id)}\">",
        "name: ${nameNormalized ?: name.orEmpty()}",
        "created: ${slackTsToUtc(created)}",
        "private: $isPrivate",
        "archived: $isArchived",
        "member: $isMember",
        "shared: $isShared",
        "members: $numOfMembers",
        topic?.value?.takeIf { it.isNotBlank() }?.let { "topic: ${it.singleLine()}" },
        purpose?.value?.takeIf { it.isNotBlank() }?.let { "purpose: ${it.singleLine()}" },
        "</channel>",
    ).joinToString("\n")

private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()

private fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun MethodsClient.fetchSlackChannelPage(
    cursor: String?,
    limit: Int,
    includeArchived: Boolean,
): SlackChannelPage {
    val response: ConversationsListResponse =
        conversationsList { req ->
            req.types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
            req.excludeArchived(!includeArchived)
            req.limit(limit)
            cursor?.let(req::cursor)
            req
        }
    if (!response.isOk) {
        throw ToolException.ValidationFailure(response.error ?: "Failed to list Slack channels.")
    }
    return SlackChannelPage(
        channels = response.channels.orEmpty(),
        nextCursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
    )
}
