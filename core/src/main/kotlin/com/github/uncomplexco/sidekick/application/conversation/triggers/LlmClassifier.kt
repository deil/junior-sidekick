package com.github.uncomplexco.sidekick.application.conversation.triggers

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class KoogReplyDecisionClassifier(
    private val agentConfig: AgentConfig,
    private val config: KoogConfig,
) : ReplyDecisionClassifier {
    override suspend fun classify(input: ReplyDecisionInput): ReplyDecision {
        val model =
            LLModel(
                provider = LLMProvider.OpenRouter,
                id = config.model,
                capabilities = config.modelCapabilities(),
                contextLength = 128_000,
            )

        val historyText = input.conversationContext?.takeIf { it.isNotBlank() } ?: "[none]"

        val prompt =
            prompt(
                id = "sidekick-reply-decision",
                params = config.openRouterParams(),
            ) {
                system(buildRouterSystemPrompt(agentConfig.name))
                user(buildRouterPrompt(input.text, historyText))
            }

        return runCatching {
            openRouterExecutor(config.openRouterApiKey).use { executor ->
                val result = executor.executeStructured<ReplyClassifierResult>(prompt, model).getOrThrow().data
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
            }
        }.getOrElse { error ->
            log.warn("Reply classifier failed", error)
            ReplyDecision(false, ReplyDecisionReason.CLASSIFIER_ERROR, error.message)
        }
    }

    private fun buildRouterSystemPrompt(agentName: String): String =
        listOf(
            "You are a message router for a Slack assistant named $agentName in a subscribed Slack thread.",
            "Decide whether $agentName should reply to the latest message.",
            "Subscribed threads are passive by default.",
            "Reply true only when the latest message is clearly aimed at $agentName.",
            "Use who currently has the conversation floor, not just topic overlap.",
            "Acknowledgments, status chatter, and human-to-human coordination should be shouldReply=false.",
            "When uncertain, prefer shouldReply=false with low confidence.",
            "Return only structured output.",
        ).joinToString("\n")

    private fun buildRouterPrompt(
        rawText: String,
        historyText: String,
    ): String =
        listOf(
            "<latest-message>${rawText.trim()}</latest-message>",
            "<recent-thread>",
            historyText,
            "</recent-thread>",
        ).joinToString("\n")

    companion object {
        private const val ROUTER_CONFIDENCE_THRESHOLD = 0.8
        private val log = LoggerFactory.getLogger(KoogReplyDecisionClassifier::class.java)
    }
}

@Serializable
data class ReplyClassifierResult(
    val shouldReply: Boolean,
    val confidence: Double,
    val reason: String? = null,
)
