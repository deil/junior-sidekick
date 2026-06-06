package com.github.uncomplexco.sidekick.application.conversation.triggers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ReplyDecisionServiceTest {
    @Test
    fun `explicit mention always replies`() =
        runBlocking {
            val service =
                ReplyDecisionService(FakeClassifier(ReplyDecision(false, ReplyDecisionReason.SIDE_CONVERSATION)))

            val decision =
                service.decide(
                    ReplyDecisionInput(
                        text = "@heytech help",
                        hasAssistantHistory = false,
                        isExplicitMention = true,
                    ),
                )

            assertEquals(ReplyDecisionReason.EXPLICIT_MENTION, decision.reason)
            assertEquals(true, decision.shouldReply)
        }

    @Test
    fun `slack user mention to someone else does not reply`() =
        runBlocking {
            val service = ReplyDecisionService(FakeClassifier(ReplyDecision(true, ReplyDecisionReason.CLASSIFIER)))

            val decision =
                service.decide(
                    ReplyDecisionInput(
                        text = "<@U039RPWU0V8> test",
                        hasAssistantHistory = true,
                    ),
                )

            assertEquals(ReplyDecisionReason.DIRECTED_TO_OTHER_PARTY, decision.reason)
            assertEquals(false, decision.shouldReply)
        }

    @Test
    fun `acknowledgment does not reply`() =
        runBlocking {
            val service = ReplyDecisionService(FakeClassifier(ReplyDecision(true, ReplyDecisionReason.CLASSIFIER)))

            val decision =
                service.decide(
                    ReplyDecisionInput(
                        text = "thanks",
                        hasAssistantHistory = true,
                    ),
                )

            assertEquals(ReplyDecisionReason.ACKNOWLEDGMENT, decision.reason)
            assertEquals(false, decision.shouldReply)
        }

    @Test
    fun `private message without assistant history still delegates to classifier`() =
        runBlocking {
            val service = ReplyDecisionService(FakeClassifier(ReplyDecision(true, ReplyDecisionReason.CLASSIFIER)))

            val decision =
                service.decide(
                    ReplyDecisionInput(
                        text = "can you help with this?",
                        hasAssistantHistory = false,
                        isPrivateMessage = true,
                    ),
                )

            assertEquals(ReplyDecisionReason.CLASSIFIER, decision.reason)
            assertEquals(true, decision.shouldReply)
        }

    @Test
    fun `delegates subscribed decision to classifier when assistant has history`() =
        runBlocking {
            val service =
                ReplyDecisionService(FakeClassifier(ReplyDecision(true, ReplyDecisionReason.CLASSIFIER, "follow-up")))

            val decision =
                service.decide(
                    ReplyDecisionInput(
                        text = "what do you mean by that?",
                        hasAssistantHistory = true,
                    ),
                )

            assertEquals(ReplyDecisionReason.CLASSIFIER, decision.reason)
            assertEquals(true, decision.shouldReply)
        }

    private class FakeClassifier(
        private val decision: ReplyDecision,
    ) : ReplyDecisionClassifier {
        override suspend fun classify(input: ReplyDecisionInput): ReplyDecision = decision
    }
}
