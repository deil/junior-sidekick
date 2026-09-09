package com.github.uncomplexco.sidekick.adapters.chat

import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChatPlatformConfiguration {
    @Bean
    fun selectedChatPlatform(
        @Value("\${adapters.chat.platform:slack}") platform: String,
        @Value("\${adapters.discord.bot.token:}") discordToken: String,
    ): ChatPlatform {
        val selected =
            when (platform) {
                "slack" -> ChatPlatform.SLACK
                "discord" -> ChatPlatform.DISCORD
                else -> error("Unknown chat platform: $platform")
            }
        require(selected != ChatPlatform.DISCORD || discordToken.isNotBlank()) {
            "adapters.discord.bot.token must be configured when adapters.chat.platform=discord"
        }
        return selected
    }
}
