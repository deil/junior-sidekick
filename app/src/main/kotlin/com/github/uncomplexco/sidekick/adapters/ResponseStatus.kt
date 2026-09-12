package com.github.uncomplexco.sidekick.adapters

import com.github.uncomplexco.sidekick.application.stats.TurnStats
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import java.util.Locale

internal fun ResponseMetadata.statusLine(): String? =
    when (this) {
        ResponseMetadata.None -> null
        ResponseMetadata.RuntimeFailure -> "`[runtime failure]`"
        is ResponseMetadata.Turn -> stats.statusLine()
    }

private fun TurnStats.statusLine(): String =
    listOfNotNull(
            profileName,
            formattedExecutionTime(),
            "${formattedTokenCount(inputTokenCount)} → ${formattedTokenCount(outputTokenCount)}",
            toolCallCount.takeIf { it > 0 }?.let { "$it tools" },
        )
        .joinToString(" · ")

private fun TurnStats.formattedExecutionTime(): String =
    if (executionTimeSeconds < SECONDS_PER_MINUTE) {
        "${executionTimeSeconds}s"
    } else {
        "${executionTimeSeconds / SECONDS_PER_MINUTE}m ${executionTimeSeconds % SECONDS_PER_MINUTE}s"
    }

private fun formattedTokenCount(tokenCount: Long): String =
    if (tokenCount < TOKEN_ABBREVIATION_THRESHOLD) {
        tokenCount.toString()
    } else {
        String.format(Locale.ROOT, "%.1fK", tokenCount / TOKEN_ABBREVIATION_DIVISOR)
    }

private const val SECONDS_PER_MINUTE = 60
private const val TOKEN_ABBREVIATION_THRESHOLD = 1_000
private const val TOKEN_ABBREVIATION_DIVISOR = 1_000.0
