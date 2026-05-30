package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.conversations.Message
import com.github.uncomplexco.sidekick.application.conversations.MessageRole
import com.slack.api.bolt.context.builtin.EventContext

fun loadThreadHistory(
    ctx: EventContext,
    threadTs: String,
    currentTs: String?,
): List<Message> {
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

            return@mapNotNull Message(
                id = it.ts,
                role = if (botMessage) MessageRole.ASSISTANT else MessageRole.USER,
                sender = if (botMessage) it.user else null,
                text = text,
                timestamp = slackTsToMillis(it.ts),
            )
        }
}

fun slackTsToMillis(ts: String): Long = (ts.toDouble().times(1000)).toLong()
