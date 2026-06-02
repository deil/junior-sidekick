package com.github.uncomplexco.sidekick.application.sessions.triggers

import org.springframework.stereotype.Service

data class ReplyDecisionInput(
    val text: String,
    val conversationContext: String? = null,
    val hasAssistantHistory: Boolean,
    val isExplicitMention: Boolean = false,
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

        startsWithOtherUserMention(text)?.let {
            return ReplyDecision(false, ReplyDecisionReason.DIRECTED_TO_OTHER_PARTY, it)
        }

        if (isAcknowledgmentOnly(text)) {
            return ReplyDecision(false, ReplyDecisionReason.ACKNOWLEDGMENT)
        }

        if (!input.hasAssistantHistory) {
            return ReplyDecision(false, ReplyDecisionReason.SIDE_CONVERSATION, "assistant_not_in_thread")
        }

        return llmClassifier.classify(input)
    }

    private fun startsWithOtherUserMention(text: String): String? {
        val match = LEADING_NAMED_MENTION_RE.find(text) ?: return null
        val name =
            match.groupValues
                .getOrNull(1)
                ?.trim()
                .orEmpty()
        if (name.isBlank()) {
            return null
        }
        return "named_mention:$name"
    }

    private fun isAcknowledgmentOnly(text: String): Boolean = ACKNOWLEDGMENT_ONLY_RE.matches(text)

    companion object {
        private val LEADING_NAMED_MENTION_RE = Regex("^\\s*@([a-z0-9._-]+)\\b[\\s,:-]*", RegexOption.IGNORE_CASE)
        private val ACKNOWLEDGMENT_ONLY_RE =
            Regex(
                "^(?:thanks(?: you)?|thank you|thx|ty|got it|sounds good|sgtm|lgtm|ok(?:ay)?|cool|nice|perfect|awesome|great|makes sense|understood|roger|yep|yup|kk|on it|will do)(?:[.!]+)?$",
                RegexOption.IGNORE_CASE,
            )
    }
}
