package com.github.uncomplexco.sidekick.application.tools.discord

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import com.github.uncomplexco.sidekick.application.tools.ToolArgumentName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_DISCORD_HISTORY_LIMIT = 100
private const val MAX_DISCORD_HISTORY_LIMIT = 200

@LLMDescription("Discord tools for reading channel and thread history")
class DiscordHistoryTools(
    private val loadChannelHistory: suspend (Int, String?) -> DiscordChannelHistoryPage,
    private val loadThreadHistory: suspend (String, Int, String?) -> DiscordThreadHistoryPage,
) : ToolSet {
    @Tool
    @LLMDescription(
        "List messages from the current Discord channel, or its parent when the conversation is a thread, newest first"
    )
    suspend fun discordChannelHistory(
        @LLMDescription(
            "Maximum number of messages to return. Defaults to $DEFAULT_DISCORD_HISTORY_LIMIT " +
                "and is capped at $MAX_DISCORD_HISTORY_LIMIT"
        )
        limit: Int? = null,
        @LLMDescription(
            "Pagination cursor from next_cursor in a prior discordChannelHistory call for the same channel"
        )
        cursor: String? = null,
    ): DiscordChannelHistoryResult {
        val requestedLimit = normalizeDiscordHistoryLimit(limit)
        val page = loadChannelHistory(requestedLimit, cursor?.trim()?.takeIf { it.isNotEmpty() })
        return DiscordChannelHistoryResult(
            channelId = page.channelId,
            count = page.messages.size,
            messages = page.messages.map { it.toDiscordHistoryMessage() },
            nextCursor = page.nextCursor,
        )
    }

    @Tool
    @LLMDescription(
        "List messages from a thread in the current Discord channel, or its parent, newest first"
    )
    suspend fun discordThreadHistory(
        @ToolArgumentName("thread_id") @LLMDescription("Discord thread ID") threadId: String,
        @LLMDescription(
            "Maximum number of messages to return. Defaults to $DEFAULT_DISCORD_HISTORY_LIMIT " +
                "and is capped at $MAX_DISCORD_HISTORY_LIMIT"
        )
        limit: Int? = null,
        @LLMDescription(
            "Pagination cursor from next_cursor in a prior discordThreadHistory call for the same thread"
        )
        cursor: String? = null,
    ): DiscordThreadHistoryResult {
        val normalizedThreadId = threadId.trim()
        validate(normalizedThreadId.isNotEmpty()) { "Discord thread_id must not be blank." }
        val page =
            loadThreadHistory(
                normalizedThreadId,
                normalizeDiscordHistoryLimit(limit),
                cursor?.trim()?.takeIf { it.isNotEmpty() },
            )
        return DiscordThreadHistoryResult(
            channelId = page.channelId,
            threadId = page.threadId,
            count = page.messages.size,
            messages = page.messages.map { it.toDiscordHistoryMessage() },
            nextCursor = page.nextCursor,
        )
    }
}

fun normalizeDiscordHistoryLimit(limit: Int?): Int {
    val value = limit ?: DEFAULT_DISCORD_HISTORY_LIMIT
    validate(value >= 1) { "Discord history limit must be greater than or equal to 1." }
    return minOf(value, MAX_DISCORD_HISTORY_LIMIT)
}

@Serializable
data class DiscordChannelHistoryResult(
    @SerialName("channel_id") val channelId: String,
    val count: Int,
    val messages: List<DiscordHistoryMessage>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class DiscordThreadHistoryResult(
    @SerialName("channel_id") val channelId: String,
    @SerialName("thread_id") val threadId: String,
    val count: Int,
    val messages: List<DiscordHistoryMessage>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class DiscordHistoryMessage(
    val id: String,
    @LLMDescription("Discord message sent time in ISO UTC")
    @SerialName("sent_at")
    val sentAt: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("is_bot") val isBot: Boolean,
    val text: String,
    @SerialName("thread_id") val threadId: String?,
)

private fun DiscordChannelHistoryMessage.toDiscordHistoryMessage() =
    DiscordHistoryMessage(
        id = id,
        sentAt = sentAt,
        userId = userId,
        username = username,
        isBot = isBot,
        text = text,
        threadId = threadId,
    )
