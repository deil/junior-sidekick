package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.IncomingChatFile
import com.github.uncomplexco.sidekick.application.sessions.ChatMessage
import com.github.uncomplexco.sidekick.application.sessions.MessageAuthor
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.ports.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.ReplyResult
import com.github.uncomplexco.sidekick.ports.ReplyToMessage
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.model.Attachment
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import com.slack.api.model.File as SlackFile

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

fun slackActivityIndicator(
    ctx: EventContext,
    threadTs: String,
): ChatActivityIndicator =
    object : ChatActivityIndicator {
        override fun start() {
            setStatus(
                status = slackAssistantStatusTexts.random(),
                loadingMessages =
                    listOf(
                        "Reading what everyone said...",
                        "Checking the important bits...",
                        "Turning context into an answer...",
                        "Avoiding confident nonsense...",
                        "Making it Slack-sized...",
                    ),
            )
        }

        override fun clear() {
            setStatus("")
        }

        private fun setStatus(
            status: String,
            loadingMessages: List<String>? = null,
        ) {
            runCatching {
                val response =
                    ctx.client().assistantThreadsSetStatus { req ->
                        req.channelId(ctx.channelId)
                        req.threadTs(threadTs)
                        req.status(status)
                        if (loadingMessages != null) {
                            req.loadingMessages(loadingMessages)
                        }
                        req
                    }
                if (!response.isOk) {
                    log.warn("Slack assistant status update failed: {}", response.error)
                }
            }.onFailure {
                log.warn("Slack assistant status update failed", it)
            }
        }
    }

private val slackAssistantStatusTexts =
    listOf(
        "consulting the tiny compiler",
        "untangling the thread",
        "thinking with tabs open",
    )

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
                author = if (botMessage) toMessageAuthor(it.user, ctx) else null,
                text = text,
                timestamp = slackTsToMillis(it.ts),
            )
        }
}

internal fun incomingChatFiles(
    files: List<SlackFile>?,
    attachments: List<Attachment>?,
): List<IncomingChatFile> {
    val directFiles = files.toIncomingChatFiles()
    return directFiles.ifEmpty {
        attachments
            .orEmpty()
            .flatMap { it.files.orEmpty() }
            .toIncomingChatFiles()
    }
}

private fun List<SlackFile>?.toIncomingChatFiles(): List<IncomingChatFile> =
    this
        .orEmpty()
        .mapNotNull { file ->
            if (file.id == null || file.urlPrivateDownload == null) return@mapNotNull null

            IncomingChatFile(
                id = file.id,
                name = file.name,
                title = file.title,
                mimetype = file.mimetype,
                filetype = file.filetype,
                urlPrivateDownload = file.urlPrivateDownload,
                permalink = file.permalink,
            )
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
