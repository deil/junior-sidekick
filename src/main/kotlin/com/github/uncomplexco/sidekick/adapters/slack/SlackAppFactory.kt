package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.conversations.ChatConversationId
import com.github.uncomplexco.sidekick.usecases.AppMentionEventHandler
import com.github.uncomplexco.sidekick.usecases.ChannelMessageEventHandler
import com.github.uncomplexco.sidekick.usecases.PrivateMessageEventHandler
import com.github.uncomplexco.sidekick.usecases.TurnContext
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.bolt.middleware.builtin.Assistant
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class HandledEventsDeduper(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = 5.minutes,
) {
    private val events = ConcurrentHashMap<String, Long>()

    fun put(
        channelId: String,
        messageId: String,
    ): Boolean {
        cleanupExpired()

        val key = "$channelId:$messageId"
        val expiresAt = clock.millis() + ttl.inWholeMilliseconds
        val alreadySeen = events.putIfAbsent(key, expiresAt)
        return alreadySeen == null
    }

    fun cleanupExpired() {
        val threshold = clock.millis()
        events.entries.removeIf { it.value <= threshold }
    }
}

@Configuration
@ConditionalOnExpression(
    $$"'${adapters.slack.bot.token:}' != '' and '${adapters.slack.bot.signing-secret:}' != ''",
)
class SlackAppFactory {
    @Bean
    fun slackApp(
        slackAdapterConfig: AppConfig,
        eventDeduper: HandledEventsDeduper,
        appMentionHandler: AppMentionEventHandler,
        privateMessageHandler: PrivateMessageEventHandler,
        channelMessageHandler: ChannelMessageEventHandler,
    ): App {
        val app = App(slackAdapterConfig)

        app.assistant(buildSlackAssistant(app, eventDeduper, privateMessageHandler))

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && eventDeduper.put(event.channel, event.ts)) {
                async(app) {
                    val conversationId = event.toConversationId()
                    appMentionHandler.handle(
                        messageId = event.ts,
                        sender = event.user,
                        text = event.text,
                        ctx =
                            TurnContext(
                                chatConversationId = conversationId,
                                historyLoader = {
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts)
                                    } else {
                                        emptyList()
                                    }
                                },
                                chat = replyInSlack(ctx, event.threadTs ?: event.ts),
                            ),
                    )
                }
            }

            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && !isBotsOwnMessage(event.botId, ctx) && !containsMention(event.text, ctx.botUserId) &&
                eventDeduper.put(event.channel, event.ts)
            ) {
                if (event.channelType != "im") {
                    async(app) {
                        channelMessageHandler.handle(
                            messageId = event.ts,
                            sender = event.user,
                            text = event.text,
                            ctx =
                                TurnContext(
                                    chatConversationId = event.toConversationId(),
                                    historyLoader = {
                                        if (event.threadTs != null) {
                                            loadThreadHistory(ctx, event.threadTs, event.ts)
                                        } else {
                                            emptyList()
                                        }
                                    },
                                    chat = replyInSlack(ctx, event.threadTs),
                                ),
                        )
                    }
                } else {
                    async(app) {
                        privateMessageHandler.handle(
                            messageId = event.ts,
                            sender = event.user,
                            text = event.text,
                            ctx =
                                TurnContext(
                                    chatConversationId = event.toDirectMessageConversationId(),
                                    historyLoader = {
                                        if (event.threadTs != null) {
                                            loadThreadHistory(ctx, event.threadTs, event.ts)
                                        } else {
                                            emptyList()
                                        }
                                    },
                                    chat = replyInSlack(ctx, event.threadTs),
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
    messageHandler: PrivateMessageEventHandler,
): Assistant {
    val assistant = Assistant(app.executorService())

    assistant.userMessage { req, ctx ->
        val text = req.event.text
        if (text.isNullOrBlank()) return@userMessage

        if (!deduper.put(req.event.channel, req.event.ts)) return@userMessage

        val conversationId = ChatConversationId(channelId = null, threadId = req.event.threadTs)
        messageHandler.handle(
            messageId = req.event.ts,
            sender = req.event.user,
            text = text,
            ctx =
                TurnContext(
                    chatConversationId = conversationId,
                    historyLoader = {
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts)
                        } else {
                            emptyList()
                        }
                    },
                    chat = replyInSlack(ctx, req.event.threadTs),
                ),
        )
    }

    return assistant
}

internal fun async(
    app: App,
    block: () -> Unit,
) {
    app.executorService().submit { block() }
}

internal fun AppMentionEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageEvent.toDirectMessageConversationId(): ChatConversationId = ChatConversationId(null, threadTs)
