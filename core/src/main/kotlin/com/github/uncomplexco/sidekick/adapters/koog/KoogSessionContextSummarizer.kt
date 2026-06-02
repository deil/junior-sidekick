package com.github.uncomplexco.sidekick.adapters.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.config.AgentConfigMeh
import com.github.uncomplexco.sidekick.application.context.PromptBuilder
import com.github.uncomplexco.sidekick.application.sessions.SessionMessage
import com.github.uncomplexco.sidekick.ports.SessionContextSummarizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class KoogSessionContextSummarizer(
    private val config: KoogConfig,
    private val promptBuilder: PromptBuilder,
) : SessionContextSummarizer {
    override suspend fun summarize(messages: List<SessionMessage>): String {
        val transcript = promptBuilder.buildThreadContext(compactions = emptyList(), history = messages).orEmpty()
        log.debug("Starting session context summarization for {} messages", messages.size)

        return runCatching {
            openRouterExecutor(config.openRouterApiKey).use { executor ->
                executor
                    .execute(
                        prompt =
                            prompt(
                                id = "sidekick-session-context-compaction",
                                params = config.openRouterParams(),
                            ) {
                                user(
                                    listOf(
                                        "Summarize the following older chat transcript segment for future assistant turns.",
                                        "Keep the summary factual and concise.",
                                        "Preserve decisions, commitments, constraints, user intent, and unresolved asks.",
                                        "Do not invent details.",
                                        "",
                                        transcript,
                                    ).joinToString("\n"),
                                )
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = config.model,
                                capabilities = config.modelCapabilities(),
                            ),
                    ).textContent()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_SUMMARY_CHARS)
                    ?.also { log.debug("Session context summarization completed") }
            }
        }.getOrElse { error ->
            log.warn("Session context summarization failed", error)
            null
        } ?: transcript.take(FALLBACK_SUMMARY_CHARS).also {
            log.debug("Using fallback session context summary excerpt")
        }
    }

    companion object {
        private const val MAX_SUMMARY_CHARS = 3500
        private const val FALLBACK_SUMMARY_CHARS = 2800
        private val log = LoggerFactory.getLogger(KoogSessionContextSummarizer::class.java)
    }
}
