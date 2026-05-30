package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.bolt.AppConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class BeansConfig {
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
    fun handledEventsDeduper(): HandledEventsDeduper = HandledEventsDeduper()
}
