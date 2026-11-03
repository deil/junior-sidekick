package com.github.uncomplexco.sidekick.adapters.chat

import kotlin.test.assertContains
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ChatPlatformConfigurationTest {
    private val runner =
        ApplicationContextRunner().withUserConfiguration(ChatPlatformConfiguration::class.java)

    @Test
    fun `discord with blank token fails context startup`() {
        runner
            .withPropertyValues("adapters.chat.platform=discord", "adapters.discord.bot.token=")
            .run { context ->
                assertContains(context.startupFailure.toString(), "adapters.discord.bot.token")
            }
    }

    @Test
    fun `unknown platform fails context startup`() {
        runner.withPropertyValues("adapters.chat.platform=teams").run { context ->
            assertContains(context.startupFailure.toString(), "Unknown chat platform: teams")
        }
    }
}
