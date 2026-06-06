package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage

fun interface SessionContextSummarizer {
    suspend fun summarize(
        conversationId: ConversationId,
        messages: List<SessionMessage>,
        files: List<SessionFileRef>,
    ): String
}
