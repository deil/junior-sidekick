package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.session.SessionMessage

fun interface SessionContextSummarizer {
    suspend fun summarize(messages: List<SessionMessage>): String
}
