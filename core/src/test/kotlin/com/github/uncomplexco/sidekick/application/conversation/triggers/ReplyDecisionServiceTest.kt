package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionInput
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionReason
import com.github.uncomplexco.sidekick.application.turn.SimpleReplyDecisionClassifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyDecisionServiceTest {
    @Test
    fun `explicit mention always replies`() {
        val classifier = SimpleReplyDecisionClassifier()

        val decision =
            classifier.classify(
                ReplyDecisionInput(
                    text = "@heytech help",
                    hasAssistantHistory = false,
                    isExplicitMention = true,
                ),
            )!!

        assertEquals(ReplyDecisionReason.EXPLICIT_MENTION, decision.reason)
        assertEquals(true, decision.shouldReply)
    }

    @Test
    fun `slack user mention to someone else does not reply`() {
        val classifier = SimpleReplyDecisionClassifier()

        val decision =
            classifier.classify(
                ReplyDecisionInput(
                    text = "<@U039RPWU0V8> test",
                    hasAssistantHistory = true,
                ),
            )!!

        assertEquals(ReplyDecisionReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
        assertEquals(false, decision.shouldReply)
    }

    @Test
    fun `acknowledgment does not reply`() {
        val classifier = SimpleReplyDecisionClassifier()

        val decision =
            classifier.classify(
                ReplyDecisionInput(
                    text = "thanks",
                    hasAssistantHistory = true,
                ),
            )!!

        assertEquals(ReplyDecisionReason.ACKNOWLEDGMENT, decision.reason)
        assertEquals(false, decision.shouldReply)
    }

    @Test
    fun `private message without assistant history delegates to llm classifier`() {
        val classifier = SimpleReplyDecisionClassifier()

        assertNull(
            classifier.classify(
                ReplyDecisionInput(
                    text = "can you help with this?",
                    hasAssistantHistory = false,
                    isPrivateMessage = true,
                ),
            ),
        )
    }

    @Test
    fun `subscribed ambiguous message delegates to llm classifier`() {
        val classifier = SimpleReplyDecisionClassifier()

        assertNull(
            classifier.classify(
                ReplyDecisionInput(
                    text = "what do you mean by that?",
                    hasAssistantHistory = true,
                ),
            ),
        )
    }
}
