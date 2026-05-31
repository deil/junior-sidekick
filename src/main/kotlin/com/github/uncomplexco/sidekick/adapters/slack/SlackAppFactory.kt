package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.sessions.ChatConversationId
import com.github.uncomplexco.sidekick.application.sessions.ChatTrigger
import com.github.uncomplexco.sidekick.ports.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import com.github.uncomplexco.sidekick.usecases.IncomingChatMessage
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.middleware.builtin.Assistant
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
        handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
    ): App {
        val app = App(slackAdapterConfig)

        app.assistant(buildSlackAssistant(app, eventDeduper, handleIncomingChatMessage))

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && eventDeduper.put(event.channel, event.ts)) {
                async(app) {
                    val conversationId = event.toConversationId()
                    handleIncomingChatMessage.handle(
                        conversationId,
                        IncomingChatMessage(
                            id = event.ts,
                            createdAtMs = slackTsToMillis(event.ts),
                            sender = toMessageAuthor(event.user, ctx),
                            text = event.text,
                            trigger = ChatTrigger.APP_MENTION,
                        ),
                        ChatPlatformAdapter(
                            historyLoader = {
                                if (event.threadTs != null) {
                                    loadThreadHistory(ctx, event.threadTs, event.ts)
                                } else {
                                    emptyList()
                                }
                            },
                            reply = replyInSlack(ctx, event.threadTs ?: event.ts),
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
                        handleIncomingChatMessage.handle(
                            event.toConversationId(),
                            IncomingChatMessage(
                                id = event.ts,
                                createdAtMs = slackTsToMillis(event.ts),
                                sender = toMessageAuthor(event.user, ctx),
                                text = event.text,
                                trigger = ChatTrigger.PASSIVE_MESSAGE,
                            ),
                            ChatPlatformAdapter(
                                historyLoader = {
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts)
                                    } else {
                                        emptyList()
                                    }
                                },
                                reply = replyInSlack(ctx, event.threadTs),
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
        if (text.isNullOrBlank()) return@userMessage

        if (!deduper.put(ctx.channelId, req.event.ts)) return@userMessage

        val conversationId = ChatConversationId(channelId = ctx.channelId, threadId = req.event.threadTs)
        runBlocking {
            handleIncomingChatMessage.handle(
                conversationId,
                IncomingChatMessage(
                    id = req.event.ts,
                    createdAtMs = slackTsToMillis(req.event.ts),
                    sender = toMessageAuthor(req.event.user, ctx),
                    text = text,
                    trigger = ChatTrigger.ASSISTANT_MESSAGE,
                ),
                ChatPlatformAdapter(
                    historyLoader = {
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts)
                        } else {
                            emptyList()
                        }
                    },
                    reply = replyInSlack(ctx, req.event.threadTs),
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
