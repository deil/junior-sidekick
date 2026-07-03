package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Message
import kotlinx.serialization.Serializable

private const val DEFAULT_SLACK_HISTORY_LIMIT = 100
private const val MAX_SLACK_HISTORY_LIMIT = 200
private const val DEFAULT_SLACK_HISTORY_SCAN_DEPTH = 1
private const val MAX_SLACK_HISTORY_SCAN_DEPTH = 5

@LLMDescription("Slack tools for reading visible channel and thread history")
class SlackHistoryTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
) : ToolSet {
    @Tool
    @LLMDescription(
        "List messages (Slack history) from current conversation's parent Slack channel. Use when the user asks for recent or historical channel context outside of this thread. Do not use when current context already answers the question",
    )
    fun slackChannelHistory(
        @LLMDescription(
            "Maximum total number of messages to return. Defaults to $DEFAULT_SLACK_HISTORY_LIMIT and is capped at $MAX_SLACK_HISTORY_LIMIT",
        )
        limit: Int? = null,
        @LLMDescription("Pagination cursor to continue from a prior call")
        cursor: String? = null,
        @LLMDescription("Oldest message ts (timestamp) for range filtering")
        oldest: String? = null,
        @LLMDescription("Latest message ts (timestamp) for range filtering")
        latest: String? = null,
        @LLMDescription(
            "Maximum Slack API pages to scan. Defaults to $DEFAULT_SLACK_HISTORY_SCAN_DEPTH and is capped at $MAX_SLACK_HISTORY_SCAN_DEPTH",
        )
        scan_depth: Int? = null,
        @LLMDescription("Should oldest/latest range bounds be inclusive")
        bounds_inclusive: Boolean? = null,
    ): SlackChannelHistoryResult {
        val requestedLimit = normalizeSlackHistoryLimit(limit)
        val scanDepth = normalizeSlackHistoryScanDepth(scan_depth)
        val channelId = ctx.conversation.conversationId.channelId

        val loadedMessages = mutableListOf<SlackMessage>()
        var nextCursor = cursor?.takeIf { it.isNotBlank() }
        var pagesScanned = 0
        while (pagesScanned < scanDepth && loadedMessages.size < requestedLimit) {
            val remaining = requestedLimit - loadedMessages.size
            val history =
                withSlackApiRetries {
                    slackClient.conversationsHistory { req ->
                        req.channel(channelId)
                        req.limit(remaining)
                        nextCursor?.let(req::cursor)
                        oldest?.takeIf { it.isNotBlank() }?.let(req::oldest)
                        latest?.takeIf { it.isNotBlank() }?.let(req::latest)
                        bounds_inclusive?.let(req::inclusive)
                        req
                    }
                }
            if (!history.isOk) {
                fail(slackHistoryError(history.error, "Failed to read Slack channel history."))
            }

            pagesScanned += 1
            loadedMessages +=
                history.messages
                    .orEmpty()
                    .take(remaining)
                    .map { it.toSlackHistoryMessage() }
            nextCursor = history.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
            if (nextCursor == null) {
                break
            }
        }

        return SlackChannelHistoryResult(
            channel_id = channelId,
            count = loadedMessages.size,
            messages = loadedMessages,
            next_cursor = nextCursor,
        )
    }

    @Tool
    @LLMDescription(
        "List messages (Slack thread history) from a given thread in current conversation's parent Slack channel. Use when the user explicitly asks for thread context outside of this thread. Do not use to monitor or read history of this thread",
    )
    fun slackThreadHistory(
        @LLMDescription("Slack thread_ts (timestamp of the thread parent message)")
        thread_ts: String,
        @LLMDescription(
            "Maximum total number of messages to return. Defaults to $DEFAULT_SLACK_HISTORY_LIMIT and is capped at $MAX_SLACK_HISTORY_LIMIT",
        )
        limit: Int?,
        @LLMDescription("Pagination cursor to continue from a prior call")
        cursor: String?,
        @LLMDescription("Oldest message ts (timestamp) for range filtering")
        oldest: String?,
        @LLMDescription("Latest message ts (timestamp) for range filtering")
        latest: String?,
        @LLMDescription(
            "Maximum Slack API pages to scan. Defaults to $DEFAULT_SLACK_HISTORY_SCAN_DEPTH and is capped at $MAX_SLACK_HISTORY_SCAN_DEPTH",
        )
        scan_depth: Int?,
        @LLMDescription("Should oldest/latest range bounds be inclusive")
        bounds_inclusive: Boolean?,
    ): SlackThreadHistoryResult {
        val requestedLimit = normalizeSlackHistoryLimit(limit)
        val scanDepth = normalizeSlackHistoryScanDepth(scan_depth)
        val threadTs = thread_ts.trim()
        val channelId = ctx.conversation.conversationId.channelId

        val loadedMessages = mutableListOf<SlackMessage>()
        var nextCursor = cursor?.takeIf { it.isNotBlank() }
        var pagesScanned = 0
        while (pagesScanned < scanDepth && loadedMessages.size < requestedLimit) {
            val remaining = requestedLimit - loadedMessages.size
            val replies =
                withSlackApiRetries {
                    slackClient.conversationsReplies { req ->
                        req.channel(channelId)
                        req.ts(threadTs)
                        req.limit(remaining)
                        nextCursor?.let(req::cursor)
                        oldest?.takeIf { it.isNotBlank() }?.let(req::oldest)
                        latest?.takeIf { it.isNotBlank() }?.let(req::latest)
                        bounds_inclusive?.let(req::inclusive)
                        req
                    }
                }
            if (!replies.isOk) {
                fail(slackHistoryError(replies.error, "Failed to read Slack thread history."))
            }

            pagesScanned += 1
            loadedMessages +=
                replies.messages
                    .orEmpty()
                    .take(remaining)
                    .map { it.toSlackHistoryMessage() }
            nextCursor = replies.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
            if (nextCursor == null) {
                break
            }
        }

        return SlackThreadHistoryResult(
            channel_id = channelId,
            thread_ts = threadTs,
            count = loadedMessages.size,
            messages = loadedMessages,
            next_cursor = nextCursor,
        )
    }
}

fun normalizeSlackHistoryLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_SLACK_HISTORY_LIMIT
    validate(value >= 1) { "Slack history limit must be greater than or equal to 1." }
    return minOf(value, MAX_SLACK_HISTORY_LIMIT)
}

fun normalizeSlackHistoryScanDepth(scanDepth: Int?): Int {
    val value = scanDepth ?: DEFAULT_SLACK_HISTORY_SCAN_DEPTH
    validate(value >= 1) { "Slack history scan_depth must be greater than or equal to 1." }
    return minOf(value, MAX_SLACK_HISTORY_SCAN_DEPTH)
}

internal fun slackHistoryError(
    error: String?,
    fallback: String,
): String =
    when (error) {
        "invalid_cursor" -> "The supplied Slack pagination cursor is no longer valid. Retry the invocation without `cursor` to start from the first page again"
        null -> fallback
        else -> error
    }

private fun Message.toSlackHistoryMessage(): SlackMessage =
    SlackMessage(
        id = ts,
        sent_at = slackMessageTsToUtc(ts),
        user = user,
        username = username,
        bot_id = botId,
        subtype = subtype,
        text = text,
        is_thread = (replyCount ?: 0) > 0,
        reply_count = replyCount ?: 0,
        thread_ts = threadTs,
        parent_user_id = parentUserId,
    )

@Serializable
data class SlackChannelHistoryResult(
    val channel_id: String,
    val count: Int,
    val messages: List<SlackMessage>,
    val next_cursor: String?,
)

@Serializable
data class SlackThreadHistoryResult(
    val channel_id: String,
    val thread_ts: String,
    val count: Int,
    val messages: List<SlackMessage>,
    val next_cursor: String?,
)

@Serializable
data class SlackMessage(
    val id: String,
    @LLMDescription("Slack message sent time in ISO UTC")
    val sent_at: String,
    val user: String?,
    val username: String?,
    val bot_id: String?,
    val subtype: String?,
    val text: String?,
    val is_thread: Boolean,
    val reply_count: Int,
    val thread_ts: String?,
    val parent_user_id: String?,
)
