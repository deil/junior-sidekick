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
        description =
            "Create and maintain a structured task list for the current session. " +
                "Use it to track progress during multi-step work and keep todo statuses current.",
    ) {
    private companion object {
        val UPDATED_AT_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
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
                "Supports Slack mrkdwn formatting."
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
        if (output == null) return args.todos

        snapshot = Snapshot(args.goal, args.todos.toList(), clock.now())
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
