package com.github.uncomplexco.sidekick.application.sessions

import com.github.uncomplexco.sidekick.application.StoredConversationProcessing
import kotlinx.serialization.Serializable

@Serializable
data class SessionMessage(
    val id: String,
    val role: MessageRole,
    val user: String? = null,
    val text: String,
    val createdAtMs: Long,
    val explicitMention: Boolean = false,
    val replied: Boolean? = null,
    val skippedReason: String? = null,
)

@Serializable
data class SessionInFlightState(
    val activeTurnId: String? = null,
    val lastCompletedAtMs: Long? = null,
)

data class SessionState(
    val id: SessionId,
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var inflight: SessionInFlightState = SessionInFlightState(),
)
