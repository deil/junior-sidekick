package com.github.uncomplexco.sidekick.application.conversation

import ai.koog.prompt.message.Message
import com.github.uncomplexco.sidekick.application.workspace.VirtualPath
import kotlinx.serialization.Serializable

data class ConversationId(
    val channelId: String,
    val threadId: String,
) {
    fun lockKey(): String =
        buildString {
            append(channelId)
            append(':')
            append(threadId ?: "session")
        }

    companion object {
        fun fromLockKey(key: String) = ConversationId(key.substringBefore(':'), key.substringAfter(':'))
    }
}

data class ConversationState(
    val id: ConversationId,
    var files: MutableList<SessionFileRef>,
    var compactions: MutableList<SessionCompaction> = mutableListOf(),
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var koogMessages: MutableList<Message> = mutableListOf(),
    var inflight: ConversationInFlightState = ConversationInFlightState(),
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
    val role: SessionMessageRole,
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
data class ConversationInFlightState(
    val activeTurnId: String? = null,
    val lastCompletedAtMs: Long? = null,
)
