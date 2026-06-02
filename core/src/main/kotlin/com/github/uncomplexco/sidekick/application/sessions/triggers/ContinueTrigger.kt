package com.github.uncomplexco.sidekick.application.sessions.triggers

import org.springframework.stereotype.Service

data class ReplyDecisionInput(
    val text: String,
    val conversationContext: String? = null,
    val hasAssistantHistory: Boolean,
    val isExplicitMention: Boolean = false,
    val isPrivateMessage: Boolean = false,
)

enum class ReplyDecisionReason {
    EXPLICIT_MENTION,
    DIRECTED_TO_OTHER_PARTY,
    EMPTY_MESSAGE,
    ACKNOWLEDGMENT,
    SIDE_CONVERSATION,
    LOW_CONFIDENCE,
    CLASSIFIER,
    CLASSIFIER_ERROR,
}

data class ReplyDecision(
    val shouldReply: Boolean,
    val reason: ReplyDecisionReason,
    val detail: String? = null,
)

interface ReplyDecisionClassifier {
    suspend fun classify(input: ReplyDecisionInput): ReplyDecision
}

@Service
class ReplyDecisionService(
    private val llmClassifier: ReplyDecisionClassifier,
) {
    suspend fun decide(input: ReplyDecisionInput): ReplyDecision {
        val text = input.text.trim()

        if (input.isExplicitMention) {
            return ReplyDecision(true, ReplyDecisionReason.EXPLICIT_MENTION)
        }

        if (text.isBlank()) {
            return ReplyDecision(false, ReplyDecisionReason.EMPTY_MESSAGE)
        }

        leadingSlackUserMention(text)?.let {
            return ReplyDecision(false, ReplyDecisionReason.DIRECTED_TO_OTHER_PARTY, it)
        }

        if (isAcknowledgmentOnly(text)) {
            return ReplyDecision(false, ReplyDecisionReason.ACKNOWLEDGMENT)
        }

        if (!input.isPrivateMessage && !input.hasAssistantHistory) {
            return ReplyDecision(false, ReplyDecisionReason.SIDE_CONVERSATION, "assistant_not_in_thread")
        }

        return llmClassifier.classify(input)
    }

    private fun leadingSlackUserMention(text: String): String? {
        val match = LEADING_SLACK_USER_MENTION_RE.find(text) ?: return null
        val userId =
            match.groupValues
                .getOrNull(1)
                ?.trim()
                .orEmpty()
        if (userId.isBlank()) {
            return null
        }

        return "slack_user_mention:$userId"
    }

    private fun isAcknowledgmentOnly(text: String): Boolean = ACKNOWLEDGMENT_ONLY_RE.matches(text)

    companion object {
        private val LEADING_SLACK_USER_MENTION_RE = Regex("^\\s*<@([A-Z0-9]+)>[\\s,:-]*")
        private val ACKNOWLEDGMENT_ONLY_RE =
            Regex(
                "^(?:thanks(?: you)?|thank you|thx|ty|got it|sounds good|sgtm|lgtm|ok(?:ay)?|cool|nice|perfect|awesome|great|makes sense|understood|roger|yep|yup|kk|on it|will do)(?:[.!]+)?$",
                RegexOption.IGNORE_CASE,
            )
    }
}
