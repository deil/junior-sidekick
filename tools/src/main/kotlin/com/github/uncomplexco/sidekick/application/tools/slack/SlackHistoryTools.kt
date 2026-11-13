package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.tools.ToolArgumentName
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Message
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_SLACK_HISTORY_LIMIT = 100

@LLMDescription("Slack tools for reading visible channel and thread history")
class SlackHistoryTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
    private val projects: ProjectsRepository,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Read a Slack thread using a message archive URL or channel and message timestamp. " +
            "Other channels must be public. May join a public channel to read it. " +
            "Do not use to monitor or read history of this thread."
    )
    fun slackThreadRead(
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
        SlackHistoryReader(slackClient, projects)
            .readThread(
                ctx.conversation.conversationId.channelId,
                url,
                channelId,
                slackTs,
                limit,
                scanDepth,
            )

    @Tool
    @LLMDescription(
        "Read Slack channel history. Defaults to the current channel. " +
            "Pass `channel_id` to read another public channel. May join a public channel to read it."
    )
    fun slackChannelRead(
        @LLMDescription(
            "Slack channel id (`C123`), mention (`<#C123>` / `<#C123|name>`), or a channel name from this workspace"
        )
        @ToolArgumentName("channel_id")
        channelId: String? = null,
        @LLMDescription(
            "Maximum total number of messages to return. Defaults to 100 and is capped at 1000"
        )
        limit: Int? = null,
        @LLMDescription(
            "Cursor from `next_cursor` in a prior result. Use null or omit it for the first page; " +
                "never invent a cursor."
        )
        cursor: String? = null,
        @LLMDescription("Oldest message timestamp (Slack ts) for range filtering.")
        oldest: String? = null,
        @LLMDescription("Latest message timestamp (Slack ts) for range filtering.")
        latest: String? = null,
        @LLMDescription("Whether oldest/latest bounds should be inclusive.")
        @ToolArgumentName("bounds_inclusive")
        boundsInclusive: Boolean? = null,
        @LLMDescription("Maximum Slack API pages to scan. Defaults to 10 and is capped at 10")
        @ToolArgumentName("scan_depth")
        scanDepth: Int? = null,
    ): SlackChannelReadResult =
        SlackHistoryReader(slackClient, projects)
            .readChannel(
                ctx.conversation.conversationId.channelId,
                channelId,
                SlackChannelReadOptions(limit, scanDepth, cursor, oldest, latest, boundsInclusive),
            )
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
@OptIn(ExperimentalSerializationApi::class)
data class SlackChannelReadResult(
    @SerialName("channel_id") val channelId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("channel_name")
    val channelName: String? = null,
    @SerialName("joined_channel") val joinedChannel: Boolean,
    val count: Int,
    val messages: List<SlackMessage>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SlackThreadHistoryResult(
    @SerialName("channel_id") val channelId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("channel_name")
    val channelName: String? = null,
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
