package com.github.uncomplexco.sidekick.application.session

import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.core.VirtualPath
import com.github.uncomplexco.sidekick.application.core.toSessionBasedPath
import kotlinx.serialization.Serializable

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
    var files: MutableList<SessionFileRef>,
    var compactions: MutableList<SessionCompaction> = mutableListOf(),
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var inflight: SessionInFlightState = SessionInFlightState(),
)

@Serializable
data class SessionFileRef(
    val id: String,
    val name: String,
    val displayName: String = id,
    val mimetype: String?,
    val filetype: String?,
    val urlPrivateDownload: String,
    val localPath: VirtualPath,
)

@Serializable
class SessionMessage(
    val id: String,
    val role: MessageRole,
    val author: MessageAuthor? = null,
    val text: String,
    val fileIds: List<String> = mutableListOf(),
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
