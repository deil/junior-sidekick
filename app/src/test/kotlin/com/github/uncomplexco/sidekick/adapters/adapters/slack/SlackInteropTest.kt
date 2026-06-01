package com.github.uncomplexco.sidekick.adapters.slack

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackInteropTest {
    @Test
    fun `detects exact slack user mention token`() {
        val text = "<@U123BOT>"
        val username = "U123BOT"

        val result = containsMention(text, username)

        assertTrue(result)
    }

    @Test
    fun `detects mention embedded in message text`() {
        val text = "hey <@U123BOT> summarize this thread"
        val username = "U123BOT"

        val result = containsMention(text, username)

        assertTrue(result)
    }

    @Test
    fun `detects target mention among multiple mentions`() {
        val text = "<@U111AAA> <@U123BOT> can you check this?"
        val username = "U123BOT"

        val result = containsMention(text, username)

        assertTrue(result)
    }

    @Test
    fun `does not detect different user mention`() {
        val text = "hey <@U999OTHER> summarize this thread"
        val username = "U123BOT"

        val result = containsMention(text, username)

        assertFalse(result)
    }

    @Test
    fun `does not treat partial user id as a mention`() {
        val username = "U123BOT"

        val longerUserIdResult = containsMention("hey <@U123BOTEXTRA> summarize this thread", username)
        val shorterUserIdResult = containsMention("hey <@U123> summarize this thread", username)

        assertFalse(longerUserIdResult)
        assertFalse(shorterUserIdResult)
    }

    @Test
    fun `does not detect malformed mention text`() {
        val username = "U123BOT"

        val missingBracketsResult = containsMention("hey @U123BOT summarize this thread", username)
        val missingClosingBracketResult = containsMention("hey <@U123BOT summarize this thread", username)
        val missingOpeningBracketResult = containsMention("hey @U123BOT> summarize this thread", username)

        assertFalse(missingBracketsResult)
        assertFalse(missingClosingBracketResult)
        assertFalse(missingOpeningBracketResult)
    }

    @Test
    fun `is case sensitive because slack user ids are case sensitive tokens`() {
        val text = "hey <@u123bot> summarize this thread"
        val username = "U123BOT"

        val result = containsMention(text, username)

        assertFalse(result)
    }
}
