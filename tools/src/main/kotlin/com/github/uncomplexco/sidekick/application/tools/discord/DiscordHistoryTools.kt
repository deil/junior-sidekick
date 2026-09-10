package com.github.uncomplexco.sidekick.application.tools.discord

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
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
            "Maximum number of messages to return. Defaults to $DEFAULT_DISCORD_HISTORY_LIMIT and is capped at $MAX_DISCORD_HISTORY_LIMIT"
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
            channel_id = page.channelId,
            count = page.messages.size,
            messages = page.messages.map { it.toDiscordHistoryMessage() },
            next_cursor = page.nextCursor,
        )
    }

    @Tool
    @LLMDescription(
        "List messages from a thread in the current Discord channel, or its parent, newest first"
    )
    suspend fun discordThreadHistory(
        @LLMDescription("Discord thread ID") thread_id: String,
        @LLMDescription(
            "Maximum number of messages to return. Defaults to $DEFAULT_DISCORD_HISTORY_LIMIT and is capped at $MAX_DISCORD_HISTORY_LIMIT"
        )
        limit: Int? = null,
        @LLMDescription(
            "Pagination cursor from next_cursor in a prior discordThreadHistory call for the same thread"
        )
        cursor: String? = null,
    ): DiscordThreadHistoryResult {
        val threadId = thread_id.trim()
        validate(threadId.isNotEmpty()) { "Discord thread_id must not be blank." }
        val page =
            loadThreadHistory(
                threadId,
                normalizeDiscordHistoryLimit(limit),
                cursor?.trim()?.takeIf { it.isNotEmpty() },
            )
        return DiscordThreadHistoryResult(
            channel_id = page.channelId,
            thread_id = page.threadId,
            count = page.messages.size,
            messages = page.messages.map { it.toDiscordHistoryMessage() },
            next_cursor = page.nextCursor,
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
    val channel_id: String,
    val count: Int,
    val messages: List<DiscordHistoryMessage>,
    val next_cursor: String?,
)

@Serializable
data class DiscordThreadHistoryResult(
    val channel_id: String,
    val thread_id: String,
    val count: Int,
    val messages: List<DiscordHistoryMessage>,
    val next_cursor: String?,
)

@Serializable
data class DiscordHistoryMessage(
    val id: String,
    @LLMDescription("Discord message sent time in ISO UTC") val sent_at: String,
    val user_id: String,
    val username: String,
    val is_bot: Boolean,
    val text: String,
    val thread_id: String?,
)

private fun DiscordChannelHistoryMessage.toDiscordHistoryMessage() =
    DiscordHistoryMessage(
        id = id,
        sent_at = sentAt,
        user_id = userId,
        username = username,
        is_bot = isBot,
        text = text,
        thread_id = threadId,
    )
