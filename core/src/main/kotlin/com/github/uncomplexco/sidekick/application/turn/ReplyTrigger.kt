package com.github.uncomplexco.sidekick.application.turn

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class ReplyDecisionInput(
    val text: String,
    val botUser: MessageAuthor,
    val messageHistory: List<SessionMessage>,
    val hasAssistantHistory: Boolean,
    val isExplicitMention: Boolean = false,
    val isPrivateMessage: Boolean = false,
)

enum class ReplyDecisionReason {
    EXPLICIT_MENTION,
    UNSUBSCRIBE_COMMAND,
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
    val shouldUnsubscribe: Boolean = false,
)

@Component
class ReplyDecisionService(
    private val simpleClassifier: SimpleReplyDecisionClassifier,
    private val llmClassifier: LlmReplyDecisionClassifier,
) {
    suspend fun shouldReply(input: ReplyDecisionInput): ReplyDecision = simpleClassifier.classify(input) ?: llmClassifier.classify(input)
}

@Component
class SimpleReplyDecisionClassifier {
    fun classify(input: ReplyDecisionInput): ReplyDecision? {
        if (input.isExplicitMention && isUnsubscribeCommand(input.text)) {
            return ReplyDecision(
                shouldReply = false,
                reason = ReplyDecisionReason.UNSUBSCRIBE_COMMAND,
                shouldUnsubscribe = true,
            )
        }

        if (input.isExplicitMention || explicitMention(input.text, input.botUser.username)) {
            return ReplyDecision(true, ReplyDecisionReason.EXPLICIT_MENTION)
        }

        val text = input.text

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
            return ReplyDecision(false, ReplyDecisionReason.SIDE_CONVERSATION, "not_subscribed_to_thread")
        }

        return null
    }

    private fun isAcknowledgmentOnly(text: String): Boolean = ACKNOWLEDGMENT_ONLY_RE.matches(text)

    private fun isUnsubscribeCommand(text: String): Boolean = UNSUBSCRIBE_COMMAND_RE.containsMatchIn(text)

    private fun explicitMention(
        text: String,
        botUsername: String,
    ): Boolean = text.contains("<@$botUsername>")

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

        return "user_mention:$userId"
    }

    companion object {
        private val LEADING_SLACK_USER_MENTION_RE = Regex("^\\s*<@([A-Z0-9]+)>[\\s,:-]*")
        private val ACKNOWLEDGMENT_ONLY_RE =
            Regex(
                "^(?:thanks(?: you)?|thank you|thx|ty|got it|sounds good|ok(?:ay)?|cool|nice|perfect|awesome|great|makes sense|understood|roger|yep|yup|kk|on it|will do)(?:[.!]+)?$",
                RegexOption.IGNORE_CASE,
            )
        private val UNSUBSCRIBE_COMMAND_RE =
            Regex(
                "\\b(?:unsubscribe|stop (?:replying|responding|participating|watching)|(?:mute|leave) this thread|don't (?:reply|participate|watch))\\b",
                RegexOption.IGNORE_CASE,
            )
    }
}

@Component
class LlmReplyDecisionClassifier(
    private val config: KoogConfig,
    private val executeClassifier: suspend (Prompt, LLModel) -> ReplyClassifierResult = { prompt, model ->
        openRouterExecutor(config.openRouterApiKey).use { executor ->
            executor.executeStructured<ReplyClassifierResult>(prompt, model).getOrThrow().data
        }
    },
) {
    suspend fun classify(input: ReplyDecisionInput): ReplyDecision {
        return runCatching {
            val model =
                LLModel(
                    provider = LLMProvider.OpenRouter,
                    id = config.model,
                    capabilities = config.modelCapabilities(),
                    contextLength = 128_000,
                )

            val historyText =
                if (input.messageHistory.isEmpty()) {
                    "[none]"
                } else {
                    input.messageHistory
                        .take(5)
                        .map {
                            val author = if (it.role == SessionMessageRole.ASSISTANT) input.botUser else it.author!!
                            return@map escapeXml("[${it.role.name}] ${author.fullName}: ${it.text}")
                        }.joinToString("\n")
                }

            val prompt =
                prompt(
                    id = "sidekick-reply-decision",
                    params = config.openRouterParams(),
                ) {
                    system(buildRouterSystemPrompt(input.botUser.fullName!!, input.botUser.username))
                    user(buildRouterPrompt(input.text, historyText, input.messageHistory.lastOrNull { it.role == SessionMessageRole.ASSISTANT }))
                }

            val result = executeClassifier(prompt, model)
            if (!result.shouldReply) {
                return ReplyDecision(
                    shouldReply = false,
                    reason =
                        if (result.confidence <
                            ROUTER_CONFIDENCE_THRESHOLD
                        ) {
                            ReplyDecisionReason.LOW_CONFIDENCE
                        } else {
                            ReplyDecisionReason.SIDE_CONVERSATION
                        },
                    detail = result.reason,
                )
            }

            if (result.confidence < ROUTER_CONFIDENCE_THRESHOLD) {
                ReplyDecision(false, ReplyDecisionReason.LOW_CONFIDENCE, result.reason)
            } else {
                ReplyDecision(true, ReplyDecisionReason.CLASSIFIER, result.reason)
            }
        }.getOrElse { error ->
            log.warn("Reply classifier failed", error)
            ReplyDecision(false, ReplyDecisionReason.CLASSIFIER_ERROR, error.message)
        }
    }

    private fun buildRouterSystemPrompt(
        agentName: String,
        botUsername: String,
    ): String =
        """
        You are a message router for a Slack assistant named $agentName (user $botUsername) in a subscribed Slack thread.
        Decide whether $agentName should reply to the latest message.
        If there are several human participants in a subscribed thread, such subscribed threads are passive by default.
        When only $agentName and one human are active - latest message can be shouldReply=true.
        Reply true only when the latest message is aimed at $agentName.
        Use who currently has the conversation floor, not just topic overlap.
        If $agentName was the last speaker terse clarifications, follow-ups, direct reference to $agentName's prior answer should count as an implicit follow-up.
        If $agentName's last message was a question or request for confirmation - acknowledgment and clarification to it should count as follow-up.
        If human's latest message contains a request, new task, even if on a different subject, or references prior work - that should count as a follow-up.
        If several humans spoke after $agentName, require a direct and clear reference to $agentName. Topic overlap is not enough.
        Acknowledgments, status chatter, and human-to-human coordination should be shouldReply=false.
        When uncertain, prefer shouldReply=false with low confidence.
        Keep the decision reason short.
        """.trimIndent()

    private fun buildRouterPrompt(
        rawText: String,
        historyText: String,
        lastBotMessage: SessionMessage?,
    ): String =
        listOf(
            "<latest-message>${rawText.trim()}</latest-message>",
            "<context>${xmlTag(
                "last-assistant-message",
                lastBotMessage?.let { "[${it.role.name}]: " + escapeXml(it.text) } ?: "[none]",
            )}</context>",
            "<recent-thread>",
            historyText,
            "</recent-thread>",
        ).joinToString("\n")

    @Serializable
    data class ReplyClassifierResult(
        val shouldReply: Boolean,
        val confidence: Double,
        val reason: String? = null,
    )

    companion object {
        private const val ROUTER_CONFIDENCE_THRESHOLD = 0.8
        private val log = LoggerFactory.getLogger(LlmReplyDecisionClassifier::class.java)
    }
}
