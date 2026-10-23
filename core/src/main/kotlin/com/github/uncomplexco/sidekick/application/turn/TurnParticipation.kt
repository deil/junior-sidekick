package com.github.uncomplexco.sidekick.application.turn

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class ParticipationClassifierMessage(
    val text: String,
    val author: MessageAuthor,
    val isExplicitMention: Boolean = false,
)

data class ParticipationClassifierInput(
    val messages: List<ParticipationClassifierMessage>,
    val botUser: MessageAuthor,
    val messageHistory: List<SessionMessage>,
    val hasAssistantHistory: Boolean,
    val isPrivateMessage: Boolean = false,
    val conversationId: ConversationId? = null,
)

enum class TurnParticipationReason {
    EXPLICIT_MENTION,
    PRIVATE_MESSAGE,
    UNSUBSCRIBE_COMMAND,
    DIRECTED_TO_OTHER_PARTY,
    EMPTY_MESSAGE,
    ACKNOWLEDGMENT,
    SIDE_CONVERSATION,
    LOW_CONFIDENCE,
    CLASSIFIER,
    CLASSIFIER_ERROR,
}

sealed interface TurnParticipationDecision {
    val reason: TurnParticipationReason
    val detail: String?

    data class Respond(
        override val reason: TurnParticipationReason,
        override val detail: String? = null,
    ) : TurnParticipationDecision

    data class Skip(
        override val reason: TurnParticipationReason,
        override val detail: String? = null,
    ) : TurnParticipationDecision

    data class Unsubscribe(
        override val reason: TurnParticipationReason,
        override val detail: String? = null,
    ) : TurnParticipationDecision
}

data class TurnParticipationInput(
    val messages: List<IncomingMessage>,
    val turn: RecordedTurn,
)

@Component
class TurnParticipation(
    private val agentConfig: AgentConfig,
    private val deterministicClassifier: DeterministicParticipationClassifier,
    private val llmClassifier: LlmParticipationClassifier,
) {
    suspend fun decide(input: TurnParticipationInput): TurnParticipationDecision {
        val turn = input.turn
        val botUser =
            MessageAuthor(
                username = agentConfig.botUsername!!,
                fullName = agentConfig.name,
            )
        val history = turn.context.conversation.history.messages
        val classifierInput =
            ParticipationClassifierInput(
                messages =
                    input.messages.map { message ->
                        ParticipationClassifierMessage(
                            text = message.text,
                            author = message.sender,
                            isExplicitMention =
                                message.type == IncomingMessageType.EXPLICIT_MENTION,
                        )
                    },
                botUser = botUser,
                messageHistory = history,
                isPrivateMessage =
                    input.messages.first().type == IncomingMessageType.ASSISTANT_MESSAGE,
                conversationId = turn.context.conversation.conversationId,
                hasAssistantHistory = history.any { it.role == SessionMessageRole.ASSISTANT },
            )

        return deterministicClassifier.classify(classifierInput)
            ?: llmClassifier.classify(classifierInput)
    }
}

@Component
class DeterministicParticipationClassifier {
    fun classify(input: ParticipationClassifierInput): TurnParticipationDecision? {
        val decisions = input.messages.map { classifyMessage(it, input) }
        val subscriptionDecision = decisions.lastOrNull {
            it is TurnParticipationDecision.Unsubscribe ||
                (it is TurnParticipationDecision.Respond &&
                    it.reason == TurnParticipationReason.EXPLICIT_MENTION)
        }
        if (subscriptionDecision != null) return subscriptionDecision

        decisions.filterIsInstance<TurnParticipationDecision.Respond>().firstOrNull()?.let {
            return it
        }
        if (decisions.any { it == null }) return null

        return decisions.firstOrNull()
    }

    private fun classifyMessage(
        message: ParticipationClassifierMessage,
        input: ParticipationClassifierInput,
    ): TurnParticipationDecision? {
        val text = message.text
        val otherParty = leadingSlackUserMention(text)

        return when {
            isUnsubscribeCommand(text) ->
                TurnParticipationDecision.Unsubscribe(TurnParticipationReason.UNSUBSCRIBE_COMMAND)
            message.isExplicitMention || explicitMention(text, input.botUser.username) ->
                TurnParticipationDecision.Respond(TurnParticipationReason.EXPLICIT_MENTION)
            text.isBlank() -> TurnParticipationDecision.Skip(TurnParticipationReason.EMPTY_MESSAGE)
            otherParty != null ->
                TurnParticipationDecision.Skip(
                    TurnParticipationReason.DIRECTED_TO_OTHER_PARTY,
                    otherParty,
                )
            isAcknowledgmentOnly(text) ->
                TurnParticipationDecision.Skip(TurnParticipationReason.ACKNOWLEDGMENT)
            input.isPrivateMessage ->
                TurnParticipationDecision.Respond(TurnParticipationReason.PRIVATE_MESSAGE)
            !input.hasAssistantHistory ->
                TurnParticipationDecision.Skip(
                    TurnParticipationReason.SIDE_CONVERSATION,
                    "not_subscribed_to_thread",
                )
            else -> null
        }
    }

    private fun isAcknowledgmentOnly(text: String): Boolean = ACKNOWLEDGMENT_ONLY_RE.matches(text)

    private fun isUnsubscribeCommand(text: String): Boolean =
        UNSUBSCRIBE_COMMAND_RE.containsMatchIn(text)

    private fun explicitMention(
        text: String,
        botUsername: String,
    ): Boolean = text.contains("<@$botUsername>")

    private fun leadingSlackUserMention(text: String): String? {
        val match = LEADING_SLACK_USER_MENTION_RE.find(text) ?: return null
        val userId = match.groupValues.getOrNull(1)?.trim().orEmpty()
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
class LlmParticipationClassifier(
    private val config: KoogConfig,
    private val executeClassifier: suspend (Prompt, LLModel) -> ParticipationClassifierResult =
        { prompt, model ->
            config.openRouterExecutor().use { executor ->
                executor
                    .executeStructured<ParticipationClassifierResult>(prompt, model)
                    .getOrThrow()
                    .data
            }
        },
) {
    suspend fun classify(input: ParticipationClassifierInput): TurnParticipationDecision {
        return runCatching {
                val aiModelProfile = config.fastProfile
                val model =
                    LLModel(
                        provider = LLMProvider.OpenRouter,
                        id = aiModelProfile.model,
                        capabilities = config.modelCapabilities(),
                        contextLength = 128_000,
                    )

                val historyText =
                    if (input.messageHistory.isEmpty()) {
                        "[none]"
                    } else {
                        input.messageHistory
                            .takeLast(5)
                            .map {
                                val author =
                                    if (it.role == SessionMessageRole.ASSISTANT) input.botUser
                                    else it.author!!
                                return@map escapeXml(
                                    "[${it.role.name}] ${author.fullName}: ${it.text}"
                                )
                            }
                            .joinToString("\n")
                    }

                val prompt =
                    prompt(
                        id = "sidekick-reply-decision",
                        params = config.openRouterParams(aiModelProfile, input.conversationId),
                    ) {
                        system(
                            buildRouterSystemPrompt(
                                input.botUser.fullName!!,
                                input.botUser.username,
                            )
                        )
                        user(
                            buildRouterPrompt(
                                input.messages,
                                historyText,
                                input.messageHistory.lastOrNull {
                                    it.role == SessionMessageRole.ASSISTANT
                                },
                            )
                        )
                    }

                val result = executeClassifier(prompt, model)
                if (!result.shouldReply) {
                    return TurnParticipationDecision.Skip(
                        reason =
                            if (result.confidence < ROUTER_CONFIDENCE_THRESHOLD) {
                                TurnParticipationReason.LOW_CONFIDENCE
                            } else {
                                TurnParticipationReason.SIDE_CONVERSATION
                            },
                        detail = result.reason,
                    )
                }

                if (result.confidence < ROUTER_CONFIDENCE_THRESHOLD) {
                    TurnParticipationDecision.Skip(
                        TurnParticipationReason.LOW_CONFIDENCE,
                        result.reason,
                    )
                } else {
                    TurnParticipationDecision.Respond(
                        TurnParticipationReason.CLASSIFIER,
                        result.reason,
                    )
                }
            }
            .getOrElse { error ->
                log.warn("Participation classifier failed", error)
                TurnParticipationDecision.Skip(
                    TurnParticipationReason.CLASSIFIER_ERROR,
                    error.message,
                )
            }
    }

    private fun buildRouterSystemPrompt(
        agentName: String,
        botUsername: String,
    ): String =
        """
        You are a message router for a Slack assistant named $agentName (user $botUsername) in a subscribed Slack thread.
        Decide whether $agentName should reply to the entire batch in <current_messages>.
        Each <message> identifies its author. Read the messages in order and consider them together, not only the last message.
        If there are several human participants in a subscribed thread, such subscribed threads are passive by default.
        When only $agentName and one human are active, the batch can be shouldReply=true.
        Reply true when at least one message in the batch is aimed at $agentName.
        Later clarifications and corrections update earlier requests. A later acknowledgment does not erase an earlier request.
        Use who currently has the conversation floor, not just topic overlap.
        If $agentName was the last speaker terse clarifications, follow-ups, direct reference to $agentName's prior answer should count as an implicit follow-up.
        If $agentName's last message was a question or request for confirmation - acknowledgment and clarification to it should count as follow-up.
        If a human message in the batch contains a request, new task, even if on a different subject, or references prior work, that should count as a follow-up.
        If several humans spoke after $agentName, require a direct and clear reference to $agentName. Topic overlap is not enough.
        A batch containing only acknowledgments, status chatter, and human-to-human coordination should be shouldReply=false.
        When uncertain, prefer shouldReply=false with low confidence.
        Keep the decision reason short.
        """
            .trimIndent()

    private fun buildRouterPrompt(
        messages: List<ParticipationClassifierMessage>,
        historyText: String,
        lastBotMessage: SessionMessage?,
    ): String = buildString {
        appendLine(
            xmlTag(
                "current_messages",
                messages.joinToString("\n") { message ->
                    val authorId = escapeXml(message.author.username)
                    val authorName = escapeXml(message.author.fullName.orEmpty())
                    "<message author_id=\"$authorId\" author_name=\"$authorName\">\n" +
                        escapeXml(message.text.trim()) +
                        "\n</message>"
                },
            )
        )
        appendLine(
            xmlTag(
                "context",
                listOf(
                        xmlTag(
                            "last_assistant_message",
                            lastBotMessage?.let { "[${it.role.name}]: " + escapeXml(it.text) }
                                ?: "[none]",
                        ),
                        xmlTag("recent_thread", historyText),
                    )
                    .joinToString("\n"),
            )
        )
    }

    @Serializable
    data class ParticipationClassifierResult(
        val shouldReply: Boolean,
        val confidence: Double,
        val reason: String? = null,
    )

    companion object {
        private const val ROUTER_CONFIDENCE_THRESHOLD = 0.8
        private val log = LoggerFactory.getLogger(LlmParticipationClassifier::class.java)
    }
}
