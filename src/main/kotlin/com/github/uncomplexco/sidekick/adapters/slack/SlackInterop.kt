package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.sessions.ChatMessage
import com.github.uncomplexco.sidekick.application.sessions.MessageAuthor
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.ports.ReplyResult
import com.github.uncomplexco.sidekick.ports.ReplyToMessage
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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

        ReplyResult(
            messageId = postResponse.ts,
            timestamp = slackTsToMillis(postResponse.ts),
        )
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

internal val usernamesCache = ConcurrentHashMap<String, MessageAuthor>()

internal fun toMessageAuthor(
    userId: String,
    ctx: EventContext,
): MessageAuthor =
    usernamesCache.computeIfAbsent(userId) {
        val userinfo = ctx.client().usersInfo { req -> req.user(userId) }

        if (!userinfo.isOk) {
            return@computeIfAbsent MessageAuthor(
                username = userId,
                fullName = null,
            )
        }

        return@computeIfAbsent MessageAuthor(
            username = userId,
            fullName =
                if (userinfo.user.profile.displayNameNormalized
                        .isNullOrBlank()
                ) {
                    userinfo.user.realName
                } else {
                    userinfo.user.profile.displayNameNormalized
                },
        )
    }

internal fun slackTsToMillis(ts: String): Long = (ts.toDouble().times(1000)).toLong()

internal fun isBotsOwnMessage(
    senderBotId: String?,
    ctx: EventContext,
): Boolean = senderBotId != null && senderBotId == ctx.botUserId

internal fun containsMention(
    text: String,
    username: String,
): Boolean = text.contains("<@$username>")

internal val log: Logger = LoggerFactory.getLogger(SlackAppFactory::class.java)
