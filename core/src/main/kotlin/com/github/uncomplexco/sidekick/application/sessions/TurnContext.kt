package com.github.uncomplexco.sidekick.application.sessions

import com.github.uncomplexco.sidekick.application.IncomingChatFile

data class TurnContext(
    val sessionId: SessionId,
    val turnId: String,
    val currentMessageId: String,
    val currentFiles: List<IncomingChatFile>,
    val compactions: List<SessionCompaction>,
    val history: List<SessionMessage>,
)
