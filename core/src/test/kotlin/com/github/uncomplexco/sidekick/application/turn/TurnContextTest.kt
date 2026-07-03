package com.github.uncomplexco.sidekick.application.conversation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TurnContextTest {
    @Test
    fun `filterOutRecentMessages removes messages with ids present in recent messages`() {
        val old = message("old")
        val duplicate = message("duplicate")
        val current = message("current")

        val result = filterOutRecentMessages(listOf(old, duplicate, current), listOf(duplicate, current))

        assertEquals(listOf(old), result)
    }

    @Test
    fun `filterOutRecentMessages keeps messages when recent messages is empty`() {
        val messages = listOf(message("one"), message("two"))

        val result = filterOutRecentMessages(messages, emptyList())

        assertEquals(messages, result)
    }

    @Test
    fun `filterOutRecentMessages compares only message ids`() {
        val historical = message("same-id", role = SessionMessageRole.USER, text = "old text")
        val recent = message("same-id", role = SessionMessageRole.ASSISTANT, text = "new text")

        val result = filterOutRecentMessages(listOf(historical), listOf(recent))

        assertEquals(emptyList(), result)
    }

    private fun message(
        id: String,
        role: SessionMessageRole = SessionMessageRole.USER,
        text: String = id,
    ): SessionMessage =
        SessionMessage(
            id = id,
            role = role,
            text = text,
            createdAtMs = 1L,
        )
}
