package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.usecases.AppMentionEventHandler
import com.github.uncomplexco.sidekick.usecases.ChannelMessageEventHandler
import com.github.uncomplexco.sidekick.usecases.PrivateMessageEventHandler
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

@Configuration
@ConditionalOnExpression("'\${adapters.slack.bot.token:}' != '' and '\${adapters.slack.bot.signing-secret:}' != ''")
class SlackAppFactory(
    val appMentionHandler: AppMentionEventHandler,
    val privateMessageHandler: PrivateMessageEventHandler,
    val channelMessageHandler: ChannelMessageEventHandler,
) {
    @Bean
    fun slackAdapterConfig(
        @Value("\${adapters.slack.bot.token:}") botToken: String,
        @Value("\${adapters.slack.bot.signing-secret:}") signingSecret: String,
    ): AppConfig =
        AppConfig
            .builder()
            .signingSecret(signingSecret)
            .singleTeamBotToken(botToken)
            .build()

    @Bean
    fun slackApp(slackAdapterConfig: AppConfig): App {
        val app = App(slackAdapterConfig)

        app.assistant(buildSlackAssistant(app, privateMessageHandler))

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank()) {
                async(app) {
                    appMentionHandler.handle(
                        channel = event.channel,
                        threadId = event.threadTs,
                        messageId = event.ts,
                        sender = event.user,
                        text = event.text,
                        historyLoader = {
                            if (event.threadTs != null) {
                                loadThreadHistory(ctx, event.threadTs, event.ts)
                            } else {
                                emptyList()
                            }
                        },
                    )
                }
            }

            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && event.botId != ctx.botUserId) {
                if (event.channelType != "im") {
                    async(app) {
                        channelMessageHandler.handle(
                            channel = event.channel,
                            threadId = event.threadTs,
                            messageId = event.ts,
                            sender = event.user,
                            text = event.text,
                            historyLoader = {
                                if (event.threadTs != null) {
                                    loadThreadHistory(ctx, event.threadTs, event.ts)
                                } else {
                                    emptyList()
                                }
                            },
                        )
                    }
                } else {
                    async(app) {
                        privateMessageHandler.handle(
                            threadId = event.threadTs,
                            messageId = event.ts,
                            sender = event.user,
                            text = event.text,
                            historyLoader = {
                                if (event.threadTs != null) {
                                    loadThreadHistory(ctx, event.threadTs, event.ts)
                                } else {
                                    emptyList()
                                }
                            },
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
    messageHandler: PrivateMessageEventHandler,
): Assistant {
    val assistant = Assistant(app.executorService())

    assistant.userMessage { req, ctx ->
        val text = req.event.text
        if (text.isNullOrBlank()) return@userMessage

        messageHandler.handle(
            threadId = req.event.threadTs,
            messageId = req.event.ts,
            sender = req.event.user,
            text = text,
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
