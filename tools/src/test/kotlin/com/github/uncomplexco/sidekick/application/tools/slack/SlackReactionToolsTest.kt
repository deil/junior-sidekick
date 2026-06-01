package com.github.uncomplexco.sidekick.application.tools.slack

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SlackReactionToolsTest {
    @Test
    fun `normalizes optional surrounding colons and lowercase`() {
        assertEquals("thumbsup", normalizeSlackReactionEmoji(":thumbsup:"))
        assertEquals("white_check_mark", normalizeSlackReactionEmoji(":WHITE_CHECK_MARK:"))
        assertEquals("thumbsup::skin-tone-6", normalizeSlackReactionEmoji(":ThumbsUp::skin-tone-6:"))
    }

    @Test
    fun `rejects blank emoji alias`() {
        assertThrows<IllegalArgumentException> {
            normalizeSlackReactionEmoji("::")
        }
    }

    @Test
    fun `rejects emoji alias that does not match slack reaction regex`() {
        assertThrows<IllegalArgumentException> {
            normalizeSlackReactionEmoji("white check mark")
        }
        assertThrows<IllegalArgumentException> {
            normalizeSlackReactionEmoji("thumbsup::skin-tone-7")
        }
        assertThrows<IllegalArgumentException> {
            normalizeSlackReactionEmoji("thumbsup::foo")
        }
    }
}
