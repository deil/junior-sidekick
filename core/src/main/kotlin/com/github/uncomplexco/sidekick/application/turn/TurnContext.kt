package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage

data class TurnContext(
    val conversationId: ConversationId,
    val turnId: String,
    val currentMessageId: String,
    val currentFiles: List<IncomingChatFile>,
    val sessionFiles: List<SessionFileRef>,
    val compactions: List<SessionCompaction>,
    val history: List<SessionMessage>,
)
