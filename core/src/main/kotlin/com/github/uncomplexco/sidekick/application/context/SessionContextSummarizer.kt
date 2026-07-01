package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage

fun interface SessionContextSummarizer {
    suspend fun summarize(
        conversationId: ConversationId,
        compactions: List<SessionCompaction>,
        messages: List<SessionMessage>,
    ): String
}
