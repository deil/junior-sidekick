package com.github.uncomplexco.sidekick.adapters.koog

import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.koog.KoogAgent
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component

@Component
class KoogSessionContextSummarizer(private val config: KoogConfig) : SessionContextSummarizer {
    override suspend fun summarize(
        conversationId: ConversationId,
        compactions: List<SessionCompaction>,
        messages: List<SessionMessage>,
    ): String {
        log.debug("Starting session context summarization for {} messages", messages.size)

        val transcript = buildString {
            if (compactions.isNotEmpty()) {
                appendLine(
                    xmlTag(
                        ContextTags.THREAD_SUMMARIES,
                        trimStart(
                            buildString {
                                compactions.forEach { compaction ->
                                    appendLine(
                                        xmlTag(ContextTags.HANDOFF_SUMMARY, compaction.summary)
                                    )
                                }
                            },
                            MAX_INPUT_SUMMARIES_CHARS,
                        ),
                    )
                )

                appendLine()
            }

            messages.forEach { message ->
                appendLine("[${message.role}] ${trimEnd(message.text, MAX_MESSAGE_CHARS)}\n")
            }
        }

        val aiModelProfile = config.fastProfile

        return runCatching {
                val execution =
                    KoogAgent(config, "sidekick-session-compaction")
                        .runSimple<String>(
                            runId = RunId(conversationId, UUID.randomUUID().toString()),
                            model = aiModelProfile,
                            input = { "${Prompts.CONTEXT_COMPACTION_PROMPT}\n$transcript" },
                        )
                val summary =
                    when (execution) {
                        is AgentRunResult.Completed -> execution.output
                        is AgentRunResult.Failed -> throw execution.cause
                    }
                summary
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_GENERATED_SUMMARY_CHARS)
                    ?.also { log.debug("Session context summarization completed") }
            }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                log.warn("Session context summarization failed", error)
                null
            }
            ?: trimStart(transcript, FALLBACK_SUMMARY_CHARS).also {
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
