package com.github.uncomplexco.sidekick.application.tools.system

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@LLMDescription("General system/runtime tools")
class SystemTools(
    private val clock: Clock = Clock.System,
    private val activity: ChatActivityIndicator? = null,
) : ToolSet {
    @Tool(TOOL_REPORT_ASSISTANT_ACTIVITY)
    @LLMDescription(
        "Report current activity to the user as a short status message. Use at the start of each work phase or a tool call. Use again when the current task, phase, or investigation direction changes. Use for status updates during multi-step work such as researching, diagnosing, searching files, running bash commands, running tests, building, deploying, etc. Messages should be short sentence-case fragments that describe what is happening now and start with a present-participle verb, for example: 'investigating the failure', 'searching files', or 'running tests', and so on.",
    )
    fun reportAssistantActivity(
        @LLMDescription("Short user-facing activity message describing what is happening now.")
        message: String,
    ): ReportAssistantActivityResult {
        activity?.`continue`(message)
        return ReportAssistantActivityResult(ok = true)
    }

    @Tool
    @LLMDescription(
        "Return current system time in UTC. Use when the user asks for current time/date context. Do not use as a substitute for historical or timezone-conversion research.",
    )
    fun currentDateTime(): SystemTimeResult {
        val now = clock.now()
        return SystemTimeResult(
            ok = true,
            unix_ms = now.toEpochMilliseconds(),
            iso_utc = now.toString(),
        )
    }

    @Tool
    @LLMDescription(
        "Convert a Unix timestamp to ISO UTC. Supports seconds and milliseconds; 11+ digit values are treated as milliseconds.",
    )
    fun timestampToIsoUtc(
        @LLMDescription("Unix timestamp as seconds or milliseconds since epoch.")
        timestamp: Long,
    ): TimestampToIsoUtcResult {
        val unit = if (timestamp in -99_999_999_999..99_999_999_999) TimestampUnit.SECONDS else TimestampUnit.MILLISECONDS
        val instant =
            runCatching {
                when (unit) {
                    TimestampUnit.SECONDS -> Instant.fromEpochSeconds(timestamp)
                    TimestampUnit.MILLISECONDS -> Instant.fromEpochMilliseconds(timestamp)
                }
            }.getOrElse { cause ->
                throw ToolException.ValidationFailure(cause.message ?: "Invalid Unix timestamp.")
            }

        return TimestampToIsoUtcResult(
            ok = true,
            timestamp = timestamp,
            interpreted_as = unit.value,
            iso_utc = instant.toString(),
        )
    }

    companion object {
        const val TOOL_REPORT_ASSISTANT_ACTIVITY = "assistant_report_status"
    }
}

private enum class TimestampUnit(
    val value: String,
) {
    SECONDS("seconds"),
    MILLISECONDS("milliseconds"),
}

@Serializable
data class SystemTimeResult(
    val ok: Boolean,
    val unix_ms: Long,
    val iso_utc: String,
)

@Serializable
data class ReportAssistantActivityResult(
    val ok: Boolean,
)

@Serializable
data class TimestampToIsoUtcResult(
    val ok: Boolean,
    val timestamp: Long,
    val interpreted_as: String,
    val iso_utc: String,
)
