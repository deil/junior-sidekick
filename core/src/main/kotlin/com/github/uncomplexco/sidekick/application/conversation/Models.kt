package com.github.uncomplexco.sidekick.application.conversation

import ai.koog.prompt.message.Message
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPath
import kotlinx.serialization.Serializable

data class ConversationId(
    val channelId: String,
    val threadId: String,
) {
    fun lockKey(): String =
        buildString {
            append(channelId)
            append(':')
            append(threadId)
        }

    companion object {
        fun fromLockKey(key: String) = ConversationId(key.substringBefore(':'), key.substringAfter(':'))
    }
}

data class ConversationState(
    val id: ConversationId,
    var files: MutableList<SessionFileRef>,
    var aiModel: AiModelProfile = AiModelProfile.NORMAL,
    var subscribed: Boolean = true,
    var compactions: MutableList<SessionCompaction> = mutableListOf(),
    var messages: MutableList<SessionMessage> = mutableListOf(),
    var koogMessages: MutableList<Message> = mutableListOf(),
    var stats: ConversationStats = ConversationStats(),
)

@Serializable
enum class AiModelProfile {
    FAST,
    NORMAL,
    ULTRATHINK,
}

@Serializable
data class ConversationSettings(
    val intelligenceLevel: AiModelProfile = AiModelProfile.NORMAL,
    val subscribed: Boolean = true,
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
    val summary: String? = null,
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
    val explicitSkillInvocation: ExplicitSkillInvocation? = null,
    var replied: Boolean? = null,
    var skippedReason: String? = null,
)

@Serializable
data class ExplicitSkillInvocation(
    val skillName: String,
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
data class ConversationStats(
    val activeTurnId: String? = null,
    val lastCompletedAtMs: Long? = null,
    val totalTokens: Int? = null,
    val messages: Int = 0,
    val toolCalls: Int = 0,
)
