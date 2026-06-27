package com.github.uncomplexco.sidekick.application.context.prompts

const val RUNTIME_CONTEXT_TAG = "runtime_context"
const val SKILLS_SECTION_TAG = "skills"
const val AVAILABLE_SKILLS_TAG = "available_skills"
const val EXPLICIT_SKILL_INVOCATION_TAG = "explicit_skill_invocation"
const val REQUESTER_TAG = "requester"
const val CURRENT_INSTRUCTION_TAG = "current_instruction"

object Prompts {
    val CONTEXT_COMPACTION_PROMPT =
        """
        Summarize the following older chat transcript segment for future assistant turns.
        Keep the summary factual and concise.
        Preserve decisions, commitments, constraints, user intent, and unresolved asks.
        Do not invent details.
        """.trimIndent()
}
