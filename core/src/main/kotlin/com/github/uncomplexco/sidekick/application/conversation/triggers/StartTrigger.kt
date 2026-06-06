package com.github.uncomplexco.sidekick.application.conversation.triggers

import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import org.springframework.stereotype.Component

enum class ChatMessageType {
    APP_MENTION,
    PASSIVE_MESSAGE,
    ASSISTANT_MESSAGE,
}

sealed interface TriggerDecision {
    data object Ignore : TriggerDecision

    data class Handle(
        val conversationId: ConversationId,
        val seedHistory: Boolean,
        val explicitMention: Boolean,
    ) : TriggerDecision
}

@Component
class ConversationTriggerPolicy(
    private val sessionManager: SessionManager,
) {
    fun decide(
        messageId: String,
        trigger: ChatMessageType,
        conversationId: ChatConversationId,
    ): TriggerDecision =
        when (trigger) {
            ChatMessageType.APP_MENTION -> {
                TriggerDecision.Handle(
                    conversationId = conversationId.sessionIdForInvitedMessage(messageId),
                    seedHistory = conversationId.isThread,
                    explicitMention = true,
                )
            }

            ChatMessageType.ASSISTANT_MESSAGE,
            -> {
                TriggerDecision.Handle(
                    conversationId = conversationId.toSessionId(),
                    seedHistory = true,
                    explicitMention = false,
                )
            }

            ChatMessageType.PASSIVE_MESSAGE -> {
                if (!conversationId.isThread) {
                    return TriggerDecision.Ignore
                }

                val sessionId = conversationId.toSessionId()
                if (!sessionManager.exists(sessionId)) {
                    return TriggerDecision.Ignore
                }

                TriggerDecision.Handle(
                    conversationId = sessionId,
                    seedHistory = false,
                    explicitMention = false,
                )
            }
        }
}

private fun ChatConversationId.sessionIdForInvitedMessage(messageId: String): ConversationId =
    if (isThread) {
        toSessionId()
    } else {
        ConversationId(channelId, messageId)
    }

internal fun ChatConversationId.toSessionId() = ConversationId(channelId, threadId!!)
