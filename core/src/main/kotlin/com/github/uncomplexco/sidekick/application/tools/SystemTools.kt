package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import kotlin.time.Clock

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
}

@Serializable
data class SystemTimeResult(
    val ok: Boolean,
    val unix_ms: Long,
    val iso_utc: String,
)
