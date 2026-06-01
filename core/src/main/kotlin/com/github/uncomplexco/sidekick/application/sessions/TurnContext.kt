package com.github.uncomplexco.sidekick.application.sessions

data class TurnContext(
    val sessionId: SessionId,
    val turnId: String,
    val currentMessageId: String,
    val compactions: List<SessionCompaction>,
    val history: List<SessionMessage>,
)
