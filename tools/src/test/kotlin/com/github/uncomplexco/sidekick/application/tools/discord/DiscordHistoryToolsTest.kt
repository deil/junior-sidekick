package com.github.uncomplexco.sidekick.application.tools.discord

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DiscordHistoryToolsTest {
    @Test
    fun `normalizes missing limit to default`() {
        assertEquals(100, normalizeDiscordHistoryLimit(null))
    }

    @Test
    fun `caps large limit`() {
        assertEquals(200, normalizeDiscordHistoryLimit(500))
    }

    @Test
    fun `rejects non-positive limit`() {
        assertThrows<ToolException.ValidationFailure> { normalizeDiscordHistoryLimit(0) }
    }

    @Test
    fun `loads current parent channel history with cursor`() = runBlocking {
        var request: Pair<Int, String?>? = null
        val tools =
            DiscordHistoryTools(
                loadChannelHistory = { limit, cursor ->
                    request = limit to cursor
                    DiscordChannelHistoryPage(
                        channelId = "123",
                        messages =
                            listOf(
                                DiscordChannelHistoryMessage(
                                    id = "456",
                                    sentAt = "2026-09-09T10:00:00Z",
                                    userId = "789",
                                    username = "Anton",
                                    isBot = false,
                                    text = "hello",
                                    threadId = "456",
                                )
                            ),
                        nextCursor = "455",
                    )
                },
                loadThreadHistory = { _, _, _ -> error("thread history should not load") },
            )

        val result = tools.discordChannelHistory(limit = 25, cursor = " 500 ")

        assertEquals(25 to "500", request)
        assertEquals("123", result.channel_id)
        assertEquals(1, result.count)
        assertEquals("456", result.messages.single().id)
        assertEquals("2026-09-09T10:00:00Z", result.messages.single().sent_at)
        assertEquals("455", result.next_cursor)
    }

    @Test
    fun `loads requested thread history with cursor`() = runBlocking {
        var request: Triple<String, Int, String?>? = null
        val tools =
            DiscordHistoryTools(
                loadChannelHistory = { _, _ -> error("channel history should not load") },
                loadThreadHistory = { threadId, limit, cursor ->
                    request = Triple(threadId, limit, cursor)
                    DiscordThreadHistoryPage(
                        channelId = "123",
                        threadId = threadId,
                        messages = emptyList(),
                        nextCursor = null,
                    )
                },
            )

        val result = tools.discordThreadHistory(thread_id = " 456 ", limit = 25, cursor = " 500 ")

        assertEquals(Triple("456", 25, "500"), request)
        assertEquals("123", result.channel_id)
        assertEquals("456", result.thread_id)
        assertEquals(0, result.count)
        assertEquals(null, result.next_cursor)
    }

    @Test
    fun `rejects blank thread id`() {
        val tools =
            DiscordHistoryTools(
                loadChannelHistory = { _, _ -> error("channel history should not load") },
                loadThreadHistory = { _, _, _ -> error("thread history should not load") },
            )

        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tools.discordThreadHistory(thread_id = " ") }
        }
    }
}
