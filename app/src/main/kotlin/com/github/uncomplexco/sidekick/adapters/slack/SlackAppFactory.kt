package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.IncomingChatMessage
import com.github.uncomplexco.sidekick.application.SharedContext
import com.github.uncomplexco.sidekick.application.sessions.ChatConversationId
import com.github.uncomplexco.sidekick.application.sessions.triggers.ChatTrigger
import com.github.uncomplexco.sidekick.ports.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.middleware.builtin.Assistant
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnExpression(
    $$"'${adapters.slack.bot.token:}' != '' and '${adapters.slack.bot.signing-secret:}' != ''",
)
class SlackAppFactory {
    @Bean
    fun slackApp(
        slackAdapterConfig: AppConfig,
        sharedContext: SharedContext,
        eventDeduper: HandledEventsDeduper,
        handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
    ): App {
        val app = App(slackAdapterConfig)
        sharedContext.slackClient = app.client()

        app.assistant(buildSlackAssistant(app, eventDeduper, handleIncomingChatMessage))

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && eventDeduper.put(event.channel, event.ts)) {
                async(app) {
                    val conversationId = event.toConversationId()
                    val responseThreadTs = event.threadTs ?: event.ts
                    handleIncomingChatMessage.handle(
                        conversationId,
                        IncomingChatMessage(
                            id = event.ts,
                            createdAtMs = slackTsToMillis(event.ts),
                            sender = toMessageAuthor(event.user, ctx),
                            text = event.text!!,
                            trigger = ChatTrigger.APP_MENTION,
                            files = incomingChatFiles(event.files, event.attachments),
                        ),
                        ChatPlatformAdapter(
                            botUsername = ctx.botUserId,
                            historyLoader = {
                                if (event.threadTs != null) {
                                    loadThreadHistory(ctx, event.threadTs, event.ts)
                                } else {
                                    emptyList()
                                }
                            },
                            reply = replyInSlack(ctx, responseThreadTs),
                            activity = slackActivityIndicator(ctx, responseThreadTs),
                        ),
                    )
                }
            }

            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && !isBotsOwnMessage(event.botId, ctx) &&
                !containsMention(
                    event.text,
                    ctx.botUserId,
                ) &&
                eventDeduper.put(event.channel, event.ts)
            ) {
                if (event.channelType != "im") {
                    async(app) {
                        val responseThreadTs = event.threadTs ?: event.ts
                        handleIncomingChatMessage.handle(
                            event.toConversationId(),
                            IncomingChatMessage(
                                id = event.ts,
                                createdAtMs = slackTsToMillis(event.ts),
                                sender = toMessageAuthor(event.user, ctx),
                                text = event.text,
                                trigger = ChatTrigger.PASSIVE_MESSAGE,
                                files = incomingChatFiles(event.files, event.attachments),
                            ),
                            ChatPlatformAdapter(
                                botUsername = ctx.botUserId,
                                historyLoader = {
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts)
                                    } else {
                                        emptyList()
                                    }
                                },
                                reply = replyInSlack(ctx, event.threadTs),
                                activity = slackActivityIndicator(ctx, responseThreadTs),
                            ),
                        )
                    }
                }
            }

            ctx.ack()
        }

        app.event(MessageFileShareEvent::class.java) { payload, ctx ->
            val event = payload.event
            val files = incomingChatFiles(event.files, event.attachments)
            if (eventDeduper.put(event.channel, event.ts)) {
                if (event.channelType != "im") {
                    async(app) {
                        val responseThreadTs = event.threadTs ?: event.ts
                        handleIncomingChatMessage.handle(
                            event.toConversationId(),
                            IncomingChatMessage(
                                id = event.ts,
                                createdAtMs = slackTsToMillis(event.ts),
                                sender = toMessageAuthor(event.user, ctx),
                                text = event.text!!,
                                trigger = ChatTrigger.PASSIVE_MESSAGE,
                                files = files,
                            ),
                            ChatPlatformAdapter(
                                botUsername = ctx.botUserId,
                                historyLoader = {
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts)
                                    } else {
                                        emptyList()
                                    }
                                },
                                reply = replyInSlack(ctx, event.threadTs),
                                activity = slackActivityIndicator(ctx, responseThreadTs),
                            ),
                        )
                    }
                }
            }

            ctx.ack()
        }

        return app
    }
}

internal fun buildSlackAssistant(
    app: App,
    deduper: HandledEventsDeduper,
    handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
): Assistant {
    val assistant = Assistant(app.executorService())

    assistant.userMessage { req, ctx ->
        val text = req.event.text
        if (text.isNullOrBlank() || !deduper.put(ctx.channelId, req.event.ts)) return@userMessage

        val conversationId = ChatConversationId(channelId = ctx.channelId, threadId = req.event.threadTs)
        val responseThreadTs = req.event.threadTs ?: req.event.ts
        runBlocking {
            handleIncomingChatMessage.handle(
                conversationId,
                IncomingChatMessage(
                    id = req.event.ts,
                    createdAtMs = slackTsToMillis(req.event.ts),
                    sender = toMessageAuthor(req.event.user, ctx),
                    text = text,
                    trigger = ChatTrigger.ASSISTANT_MESSAGE,
                    files = emptyList(),
                ),
                ChatPlatformAdapter(
                    botUsername = ctx.botUserId,
                    historyLoader = {
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts)
                        } else {
                            emptyList()
                        }
                    },
                    reply = replyInSlack(ctx, req.event.threadTs),
                    activity = slackActivityIndicator(ctx, responseThreadTs),
                ),
            )
        }
    }

    assistant.userMessageWithFiles { req, ctx ->
        val text = req.event.text
        if (!deduper.put(ctx.channelId, req.event.ts)) return@userMessageWithFiles

        val conversationId = ChatConversationId(channelId = ctx.channelId, threadId = req.event.threadTs)
        val responseThreadTs = req.event.threadTs ?: req.event.ts
        runBlocking {
            handleIncomingChatMessage.handle(
                conversationId,
                IncomingChatMessage(
                    id = req.event.ts,
                    createdAtMs = slackTsToMillis(req.event.ts),
                    sender = toMessageAuthor(req.event.user, ctx),
                    text = text!!,
                    trigger = ChatTrigger.ASSISTANT_MESSAGE,
                    files = incomingChatFiles(req.event.files, req.event.attachments),
                ),
                ChatPlatformAdapter(
                    botUsername = ctx.botUserId,
                    historyLoader = {
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts)
                        } else {
                            emptyList()
                        }
                    },
                    reply = replyInSlack(ctx, req.event.threadTs),
                    activity = slackActivityIndicator(ctx, responseThreadTs),
                ),
            )
        }
    }

    return assistant
}

internal fun async(
    app: App,
    block: suspend () -> Unit,
) {
    app.executorService().submit { runBlocking { block() } }
}

internal fun AppMentionEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageFileShareEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)
