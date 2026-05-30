package com.github.uncomplexco.sidekick.application.agent

internal fun formatUserMessage(
    user: String,
    text: String,
): String =
    buildString {
        appendLine("<current-message>")
        appendLine("[$user] $text")
        append("</current-message>")
    }
