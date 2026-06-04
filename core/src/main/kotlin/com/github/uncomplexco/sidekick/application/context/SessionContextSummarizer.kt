package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionMessage

fun interface SessionContextSummarizer {
    suspend fun summarize(
        sessionId: SessionId,
        messages: List<SessionMessage>,
        files: List<SessionFileRef>,
    ): String
}
