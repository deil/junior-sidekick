package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.tools.ToolArgumentName
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Message
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_SLACK_HISTORY_LIMIT = 100
private const val MAX_SLACK_HISTORY_LIMIT = 200
private const val DEFAULT_SLACK_HISTORY_SCAN_DEPTH = 1
private const val MAX_SLACK_HISTORY_SCAN_DEPTH = 5

@LLMDescription("Slack tools for reading visible channel and thread history")
class SlackHistoryTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
    private val projects: ProjectsRepository,
) : ToolSet {
    @Tool
    @LLMDescription(
        "List messages (Slack history) from current conversation's parent Slack channel. Use when " +
            "the user asks for recent or historical channel context outside of this thread. Do not " +
            "use when current context already answers the question"
    )
    fun slackChannelHistory(
        @LLMDescription(
            "Maximum total number of messages to return. Defaults to $DEFAULT_SLACK_HISTORY_LIMIT " +
                "and is capped at $MAX_SLACK_HISTORY_LIMIT"
        )
        limit: Int? = null,
        @LLMDescription("Pagination cursor to continue from a prior call") cursor: String? = null,
        @LLMDescription("Oldest message ts (timestamp) for range filtering") oldest: String? = null,
        @LLMDescription("Latest message ts (timestamp) for range filtering") latest: String? = null,
        @LLMDescription(
            "Maximum Slack API pages to scan. Defaults to $DEFAULT_SLACK_HISTORY_SCAN_DEPTH " +
                "and is capped at $MAX_SLACK_HISTORY_SCAN_DEPTH"
        )
        @ToolArgumentName("scan_depth")
        scanDepth: Int? = null,
        @LLMDescription("Should oldest/latest range bounds be inclusive")
        @ToolArgumentName("bounds_inclusive")
        boundsInclusive: Boolean? = null,
    ): SlackChannelHistoryResult {
        val requestedLimit = normalizeSlackHistoryLimit(limit)
        val normalizedScanDepth = normalizeSlackHistoryScanDepth(scanDepth)
        val channelId = ctx.conversation.conversationId.channelId

        val loadedMessages = mutableListOf<SlackMessage>()
        var nextCursor = cursor?.takeIf { it.isNotBlank() }
        var pagesScanned = 0
        while (pagesScanned < normalizedScanDepth && loadedMessages.size < requestedLimit) {
            val remaining = requestedLimit - loadedMessages.size
            val history =
                withSlackRetries("Read Slack channel history channel=$channelId") {
                    slackClient.conversationsHistory { req ->
                        req.channel(channelId)
                        req.limit(remaining)
                        nextCursor?.let(req::cursor)
                        oldest?.takeIf { it.isNotBlank() }?.let(req::oldest)
                        latest?.takeIf { it.isNotBlank() }?.let(req::latest)
                        boundsInclusive?.let(req::inclusive)
                        req
                    }
                }
            if (!history.isOk) {
                fail(slackHistoryError(history.error, "Failed to read Slack channel history."))
            }

            pagesScanned += 1
            loadedMessages +=
                history.messages.orEmpty().take(remaining).map { it.toSlackHistoryMessage() }
            nextCursor = history.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
            if (nextCursor == null) {
                break
            }
        }

        return SlackChannelHistoryResult(
            channelId = channelId,
            count = loadedMessages.size,
            messages = loadedMessages,
            nextCursor = nextCursor,
        )
    }

    @Tool
    @LLMDescription(
        "Read a Slack thread using a message archive URL or channel and message timestamp. " +
            "Other channels must be public. May join a public channel to read it. " +
            "Do not use to monitor or read history of this thread."
    )
    fun slackThreadHistory(
        @LLMDescription("Slack message archive URL") url: String? = null,
        @LLMDescription(
            "Slack message id (timestamp). Can be thread root or any child message in the thread"
        )
        @ToolArgumentName("slack_ts")
        slackTs: String? = null,
        @LLMDescription(
            "Slack channel id (`C123`), mention (`<#C123>` / `<#C123|name>`), or a channel name from this workspace"
        )
        @ToolArgumentName("channel_id")
        channelId: String? = null,
        @LLMDescription(
            "Maximum total number of messages to return. Defaults to $DEFAULT_SLACK_HISTORY_LIMIT " +
                "and is capped at 1000"
        )
        limit: Int? = null,
        @LLMDescription("Maximum Slack API pages to scan. Defaults to 10 and is capped at 10")
        @ToolArgumentName("scan_depth")
        scanDepth: Int? = null,
    ): SlackThreadHistoryResult =
        SlackThreadHistoryReader(slackClient, projects)
            .read(
                ctx.conversation.conversationId.channelId,
                url,
                channelId,
                slackTs,
                limit,
                scanDepth,
            )
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
        "invalid_cursor" ->
            "The supplied Slack pagination cursor is no longer valid. Retry the invocation without `cursor` to start from the first page again"
        null -> fallback
        else -> error
    }

internal fun Message.toSlackHistoryMessage(): SlackMessage =
    SlackMessage(
        id = ts,
        sentAt = slackMessageTsToUtc(ts),
        user = user,
        username = username,
        botId = botId,
        subtype = subtype,
        text = text,
        isThread = (replyCount ?: 0) > 0,
        replyCount = replyCount ?: 0,
        threadTs = threadTs,
        parentUserId = parentUserId,
    )

@Serializable
data class SlackChannelHistoryResult(
    @SerialName("channel_id") val channelId: String,
    val count: Int,
    val messages: List<SlackMessage>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class SlackThreadHistoryResult(
    @SerialName("channel_id") val channelId: String,
    @SerialName("channel_name") val channelName: String,
    @SerialName("joined_channel") val joinedChannel: Boolean,
    @SerialName("thread_ts") val threadTs: String,
    val count: Int,
    val messages: List<SlackMessage>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class SlackMessage(
    val id: String,
    @LLMDescription("Slack message sent time in ISO UTC") @SerialName("sent_at") val sentAt: String,
    val user: String?,
    val username: String?,
    @SerialName("bot_id") val botId: String?,
    val subtype: String?,
    val text: String?,
    @SerialName("is_thread") val isThread: Boolean,
    @SerialName("reply_count") val replyCount: Int,
    @SerialName("thread_ts") val threadTs: String?,
    @SerialName("parent_user_id") val parentUserId: String?,
)
