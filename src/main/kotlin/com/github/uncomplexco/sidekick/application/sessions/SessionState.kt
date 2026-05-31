package com.github.uncomplexco.sidekick.application.sessions

import kotlinx.serialization.Serializable

@Serializable
data class SessionCompaction(
    val id: String,
    val createdAtMs: Long,
    val summary: String,
    val coveredMessageIds: List<String>,
    val assistantMessageCount: Int = 0,
)

@Serializable
class SessionMessage(
    val id: String,
    val role: MessageRole,
    val author: MessageAuthor? = null,
    val text: String,
    val createdAtMs: Long,
    val explicitMention: Boolean = false,
    var replied: Boolean? = null,
    var skippedReason: String? = null,
)

@Serializable
data class SessionInFlightState(
    val activeTurnId: String? = null,
    val lastCompletedAtMs: Long? = null,
)

data class SessionState(
    val id: SessionId,
    var compactions: MutableList<SessionCompaction> = mutableListOf(),
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var inflight: SessionInFlightState = SessionInFlightState(),
)
