package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.session.IncomingChatFile
import com.github.uncomplexco.sidekick.application.session.SessionCompaction
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionMessage

data class TurnContext(
    val sessionId: SessionId,
    val turnId: String,
    val currentMessageId: String,
    val currentFiles: List<IncomingChatFile>,
    val compactions: List<SessionCompaction>,
    val history: List<SessionMessage>,
)
