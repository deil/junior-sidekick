package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.usecases.AppMentionEventHandler
import com.github.uncomplexco.sidekick.usecases.ChannelMessageEventHandler
import com.github.uncomplexco.sidekick.usecases.PrivateMessageEventHandler
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
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
    @Value("\${adapters.slack.bot.token:}") val botToken: String,
    @Value("\${adapters.slack.bot.signing-secret:}") val signingSecret: String,
    val appMentionHandler: AppMentionEventHandler,
    val privateMessageHandler: PrivateMessageEventHandler,
    val channelMessageHandler: ChannelMessageEventHandler,
) {
    @Bean
    fun slackApp(): App {
        val config =
            AppConfig
                .builder()
                .signingSecret(signingSecret)
                .singleTeamBotToken(botToken)
                .build()

        val app = App(config)

        app.assistant(buildAssistant(app))

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank()) {
                appMentionHandler.handle(
                    channel = event.channel,
                    sender = event.user,
                    text = event.text,
                )
            }

            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && event.botId != ctx.botUserId) {
                if (event.channelType != "im") {
                    channelMessageHandler.handle(
                        channel = event.channel,
                        threadId = event.threadTs,
                        messageId = event.ts,
                        sender = event.user,
                        text = event.text,
                    )
                } else {
                    privateMessageHandler.handle(
                        threadId = event.threadTs,
                        messageId = event.ts,
                        sender = event.user,
                        text = event.text,
                    )
                }
            }

            ctx.ack()
        }

        return app
    }

    private fun buildAssistant(app: App): Assistant {
        val assistant = Assistant(app.executorService())

        assistant.userMessage { req, ctx ->
            val text = req.event.text
            if (text.isNullOrBlank()) return@userMessage

            privateMessageHandler.handle(
                threadId = req.event.threadTs,
                messageId = req.event.ts,
                sender = req.event.user,
                text = text,
            )
        }

        return assistant
    }
}
