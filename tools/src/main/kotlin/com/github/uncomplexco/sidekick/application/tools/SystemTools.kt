package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@LLMDescription("General system/runtime tools")
class SystemTools(
    private val clock: Clock = Clock.System,
) : ToolSet {
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
}

private enum class TimestampUnit(val value: String) {
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
data class TimestampToIsoUtcResult(
    val ok: Boolean,
    val timestamp: Long,
    val interpreted_as: String,
    val iso_utc: String,
)
