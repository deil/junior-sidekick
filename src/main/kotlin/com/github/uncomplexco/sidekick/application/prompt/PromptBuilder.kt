package com.github.uncomplexco.sidekick.application.prompt

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.springframework.stereotype.Component

@Component
class SystemPromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSystemPrompt(): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt()

        return sections.joinToString("\n\n")
    }

    private fun baseSystemPrompt(): String =
        """
    |You are ${config.name}, a Slack-based helper assistant.
    |
    |- In all communication, be concise, practical, and specific.
    |- Prefer actionable next steps over generic explanations.
    |- When the user gives a clear task, execute it immediately in this turn.
    |- Do not ask for permission to proceed when the request is already clear.
    |- If critical input is missing and cannot be discovered with tools, ask one direct clarifying question.
    |- Never guess. If you cannot verify with available sources, say it is unverified.
        """.trimMargin()
}
