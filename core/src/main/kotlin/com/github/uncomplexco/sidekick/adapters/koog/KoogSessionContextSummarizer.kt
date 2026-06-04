package com.github.uncomplexco.sidekick.adapters.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.context.SessionContextSummarizer
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class KoogSessionContextSummarizer(
    private val config: KoogConfig,
    private val turnPromptBuilder: TurnPromptBuilder,
) : SessionContextSummarizer {
    override suspend fun summarize(
        sessionId: SessionId,
        messages: List<SessionMessage>,
        files: List<SessionFileRef>,
    ): String {
        val transcript =
            turnPromptBuilder
                .buildThreadContext(
                    sessionId = sessionId,
                    compactions = emptyList(),
                    history = messages,
                    sessionFiles = files,
                ).orEmpty()
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
