package com.github.uncomplexco.sidekick.application.context.prompts

object ContextTags {
    const val THREAD_SUMMARIES = "thread_summaries"
    const val HANDOFF_SUMMARY = "handoff_summary"
    const val RUNTIME_CONTEXT_TAG = "runtime_context"
    const val SKILLS_SECTION_TAG = "skills"
    const val AVAILABLE_SKILLS_TAG = "available_skills"
    const val EXPLICIT_SKILL_INVOCATION_TAG = "explicit_skill_invocation"
    const val REQUESTER_TAG = "requester"
    const val THREAD_TRANSCRIPT = "thread_transcript"
    const val CURRENT_INSTRUCTION_TAG = "current_instruction"
}

object Prompts {
    val SUBAGENT_SYSTEM_PROMPT =
        """
        You are an autonomous subagent. Complete the requested task using the available tools.
        Do not ask the user questions. Do not attempt to edit files or mutate external state.
        Use tools when they help. Return only your final answer to the parent agent.
        """.trimIndent()

    val CONTEXT_COMPACTION_PROMPT =
        """
        You are performing session transcript summarization.
        Create a concise handoff summary for another AI agent that will continue this conversation thread.

        Include:
          - Current outstanding asks
          - Key decisions, completed work, outcomes and learnings
          - Durable constraints, user preferences, IDs, URLs, artifacts; file, canvas, channel links; file paths, and auth state
          - Clear next steps and unresolved blockers

        Do not invent details. Do not include raw secrets or credentials.
        """.trimIndent()

    val TURN_HANDOFF_HEADER = "Thread handoff summary for future assistant turns:"
}
