package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.conversations.ChatMessage
import com.github.uncomplexco.sidekick.application.conversations.MessageRole
import com.github.uncomplexco.sidekick.ports.ReplyToMessage
import com.slack.api.bolt.context.builtin.EventContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun replyInSlack(
    ctx: EventContext,
    threadTs: String?,
): ReplyToMessage =
    { text ->
        val postResponse =
            ctx.client().chatPostMessage { req ->
                req.channel(ctx.channelId)
                if (threadTs != null) {
                    req.threadTs(threadTs)
                }

                req.markdownText(text)
            }

        if (!postResponse.isOk) {
            log.warn("Slack markdown post failed, fallback to plain text")
            ctx.say(text)
        }
    }

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
                sender = if (botMessage) it.user else null,
                text = text,
                timestamp = slackTsToMillis(it.ts),
            )
        }
}

fun slackTsToMillis(ts: String): Long = (ts.toDouble().times(1000)).toLong()

internal val log: Logger = LoggerFactory.getLogger(SlackAppFactory::class.java)
