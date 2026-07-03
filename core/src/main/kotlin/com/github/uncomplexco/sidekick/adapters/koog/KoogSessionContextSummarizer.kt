package com.github.uncomplexco.sidekick.adapters.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.context.SessionContextSummarizer
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags
import com.github.uncomplexco.sidekick.application.context.prompts.Prompts
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.application.utils.trimEnd
import com.github.uncomplexco.sidekick.application.utils.trimStart
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import org.springframework.stereotype.Component

@Component
class KoogSessionContextSummarizer(
    private val config: KoogConfig,
) : SessionContextSummarizer {
    override suspend fun summarize(
        conversationId: ConversationId,
        compactions: List<SessionCompaction>,
        messages: List<SessionMessage>,
    ): String {
        log.debug("Starting session context summarization for {} messages", messages.size)

        val transcript =
            buildString {
                if (!compactions.isEmpty()) {
                    appendLine(
                        xmlTag(
                            ContextTags.THREAD_SUMMARIES,
                            trimStart(
                                buildString {
                                    compactions.forEach { compaction ->
                                        appendLine(xmlTag(ContextTags.HANDOFF_SUMMARY, compaction.summary))
                                    }
                                },
                                MAX_INPUT_SUMMARIES_CHARS,
                            ),
                        ),
                    )

                    appendLine()
                }

                messages.forEach { message ->
                    appendLine("[${message.role}] ${trimEnd(message.text, MAX_MESSAGE_CHARS)}\n")
                }
            }

        val aiModelProfile = config.fastProfile

        return runCatching {
            openRouterExecutor(config.openRouterApiKey).use { executor ->
                executor
                    .execute(
                        prompt =
                            prompt(
                                id = "sidekick-session-context-compaction",
                                params = config.openRouterParams(aiModelProfile),
                            ) {
                                user("${Prompts.CONTEXT_COMPACTION_PROMPT}\n$transcript")
                            },
                        model =
                            LLModel(
                                provider = LLMProvider.OpenRouter,
                                id = aiModelProfile.model,
                                capabilities = config.modelCapabilities(),
                            ),
                    ).textContent()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_GENERATED_SUMMARY_CHARS)
                    ?.also { log.debug("Session context summarization completed") }
            }
        }.getOrElse { error ->
            log.warn("Session context summarization failed", error)
            null
        } ?: trimStart(transcript, FALLBACK_SUMMARY_CHARS).also {
            log.debug("Using fallback session context summary excerpt")
        }
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 4000
        const val MAX_INPUT_SUMMARIES_CHARS = 1000
        const val MAX_GENERATED_SUMMARY_CHARS = 3500
        const val FALLBACK_SUMMARY_CHARS = 40000
        private val log = Loggers.CONTEXT
    }
}
