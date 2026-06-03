package com.github.uncomplexco.sidekick.application.session

import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.github.uncomplexco.sidekick.application.core.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class SessionId(
    val channelId: String,
    val threadId: String,
) {
    fun lockKey(): String =
        buildString {
            append(channelId)
            append(':')
            append(threadId ?: "session")
        }
}

data class SessionState(
    val id: SessionId,
    var compactions: MutableList<SessionCompaction> = mutableListOf(),
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var inflight: SessionInFlightState = SessionInFlightState(),
)

@Serializable
class SessionMessage(
    val id: String,
    val role: MessageRole,
    val author: MessageAuthor? = null,
    val text: String,
    @Transient
    val files: List<IncomingChatFile> = emptyList(),
    val createdAtMs: Long,
    val explicitMention: Boolean = false,
    var replied: Boolean? = null,
    var skippedReason: String? = null,
)

@Serializable
data class SessionCompaction(
    val id: String,
    val createdAtMs: Long,
    val summary: String,
    val coveredMessageIds: List<String>,
    val assistantMessageCount: Int = 0,
)

@Serializable
data class SessionInFlightState(
    val activeTurnId: String? = null,
    val lastCompletedAtMs: Long? = null,
)
