package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.sessions.SessionMessage

fun interface SessionContextSummarizer {
    suspend fun summarize(messages: List<SessionMessage>): String
}
