package com.github.uncomplexco.sidekick.application.tools.todos

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TodoTool(
    private val output: TurnLifecycle? = null,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
) :
    Tool<TodoTool.Args, List<TodoTool.Item>>(
        argsType = typeToken<Args>(),
        resultType = typeToken<List<Item>>(),
        name = "Todo",
        description = DESCRIPTION,
    ) {
    private companion object {
        val UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

        @Suppress("MaxLineLength")
        val DESCRIPTION =
            """
            Create and maintain a structured task list for the current session. Tracks progress, organizes multi-step work, and surfaces status to the user.

            ## When to use
            Use proactively when:
            - The task requires 3+ distinct steps or actions (not just 3 tool calls for a single conceptual step)
            - The work is non-trivial and benefits from planning
            - The user provides multiple tasks (numbered or comma-separated) or explicitly asks for a todo list
            - New instructions arrive - capture them as todos
            - You start a task - mark it `in_progress` (only one at a time) before working
            - You finish a task - mark it `completed` and add any follow-ups discovered during the work

            ## When NOT to use
            Skip when:
            - The work is a single, straightforward task (or <3 trivial steps)
            - The request is purely informational or conversational
            - Tracking adds no organizational value

            ## States
            - `pending` - not started
            - `in_progress` - actively working (exactly ONE at a time)
            - `completed` - finished successfully
            - `cancelled` - no longer needed

            ## Rules
            - Update status in real time; don't batch completions
            - Mark `completed` only after the required work is actually done, including any required verification. Never based on intent.
            - Keep exactly one `in_progress` while work remains
            - If blocked or partial, keep it `in_progress` and add a follow-up todo describing the blocker
            - Preserve user-provided commands verbatim (flags, args, order)
            - Items should be specific and actionable; break large work into smaller steps

            ## Examples

            Use it:
            - "Add a dark mode toggle and run the tests" -> multi-step feature + explicit verification
            - "Rename getCwd -> getCurrentWorkingDirectory across the repo" -> grep reveals 15 occurrences in 8 files
            - "Implement registration, catalog, cart, checkout" -> multiple complex features

            Skip it:
            - "How do I print Hello World in Python?" -> informational
            - "Add a comment to calculateTotal" -> single edit
            - "Run npm install and tell me what happened" -> one command

            When in doubt, use it.
            """
                .trimIndent()
    }

    private data class Snapshot(val goal: String, val todos: List<Item>, val updatedAt: Instant) {
        val finished: Boolean
            get() = todos.all { it.status == Status.COMPLETED || it.status == Status.CANCELLED }
    }

    private lateinit var snapshot: Snapshot

    @Serializable
    data class Args(
        @property:LLMDescription(
            "Brief description of the current user's ask, focusing on the desired outcome. " +
                "Supports the current chat platform's Markdown formatting."
        )
        val goal: String,
        @property:LLMDescription("The updated todo list") val todos: List<Item>,
    )

    @Serializable
    data class Item(
        @property:LLMDescription("Brief description of the task") val content: String,
        @property:LLMDescription(
            "Current status of the task: pending, in_progress, completed, cancelled"
        )
        val status: Status,
        @property:LLMDescription("Priority level of the task: high, medium, low")
        val priority: Priority,
    )

    @Serializable
    enum class Status(val icon: String) {
        @SerialName("pending") PENDING("◦"),
        @SerialName("in_progress") IN_PROGRESS("∗"),
        @SerialName("completed") COMPLETED("✓"),
        @SerialName("cancelled") CANCELLED("×"),
    }

    @Serializable
    enum class Priority {
        @SerialName("high") HIGH,
        @SerialName("medium") MEDIUM,
        @SerialName("low") LOW,
    }

    override suspend fun execute(args: Args): List<Item> {
        snapshot = Snapshot(args.goal, args.todos.toList(), clock.now())
        if (output == null) return args.todos

        output.reportStatus(
            renderCurrentSnapshot(),
            key = "todos",
            refresh = if (snapshot.finished) null else ::renderCurrentSnapshot,
        )
        return args.todos
    }

    private fun renderCurrentSnapshot(): String {
        val current = snapshot
        val localTime =
            LocalDateTime.ofInstant(current.updatedAt.toJavaInstant(), zone)
                .format(UPDATED_AT_FORMAT)
        val body = buildString {
            append(current.goal)
            appendLine()
            if (current.todos.isEmpty()) append("\nNo tasks.")
            current.todos.forEach { item ->
                append("\n${item.status.icon} ${item.content}")
            }
        }
        val minutes = (clock.now() - current.updatedAt).inWholeMinutes
        val age =
            when {
                current.finished -> ""
                minutes == 0L -> " (just now)"
                else -> " ($minutes ${if (minutes == 1L) "minute" else "minutes"} ago)"
            }
        return "$body\n\n_todos as of $localTime${age}_"
    }
}
