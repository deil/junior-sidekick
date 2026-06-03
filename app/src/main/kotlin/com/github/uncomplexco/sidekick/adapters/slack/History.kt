package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.ports.ChatMessage
import com.slack.api.bolt.context.builtin.EventContext

fun loadThreadHistory(
    ctx: EventContext,
    threadTs: String,
    currentTs: String?,
): List<ChatMessage> {
    val response =
        ctx.client().conversationsReplies { req ->
            req.channel(ctx.channelId)
            req.ts(threadTs)
        }

    if (!response.isOk) {
        return emptyList()
    }

    return response.messages
        .orEmpty()
        .mapNotNull {
            val text = it.text.trim()
            if (text.isBlank() || (currentTs != null && it.ts == currentTs)) return@mapNotNull null

            val botMessage = it.botId != null && it.botId == ctx.botUserId

            return@mapNotNull ChatMessage(
                id = it.ts,
                role = if (botMessage) MessageRole.ASSISTANT else MessageRole.USER,
                author = if (!botMessage) toMessageAuthor(it.user, ctx) else null,
                text = text,
                timestamp = slackTsToMillis(it.ts),
            )
        }
}
