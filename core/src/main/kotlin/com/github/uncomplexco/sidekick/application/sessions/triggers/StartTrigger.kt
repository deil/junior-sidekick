package com.github.uncomplexco.sidekick.application.sessions.triggers

import com.github.uncomplexco.sidekick.application.sessions.AgentSessions
import com.github.uncomplexco.sidekick.application.sessions.ChatConversationId
import com.github.uncomplexco.sidekick.application.sessions.SessionId
import com.github.uncomplexco.sidekick.application.sessions.toSessionId
import org.springframework.stereotype.Component

enum class ChatTrigger {
    APP_MENTION,
    PASSIVE_MESSAGE,
    ASSISTANT_MESSAGE,
}

sealed interface TriggerDecision {
    data object Ignore : TriggerDecision

    data class Handle(
        val sessionId: SessionId,
        val seedHistory: Boolean,
        val explicitMention: Boolean,
    ) : TriggerDecision
}

@Component
class ConversationTriggerPolicy(
    private val agentSessions: AgentSessions,
) {
    fun decide(
        messageId: String,
        trigger: ChatTrigger,
        conversationId: ChatConversationId,
    ): TriggerDecision =
        when (trigger) {
            ChatTrigger.APP_MENTION -> {
                TriggerDecision.Handle(
                    sessionId = conversationId.sessionIdForInvitedMessage(messageId),
                    seedHistory = conversationId.isThread,
                    explicitMention = true,
                )
            }

            ChatTrigger.ASSISTANT_MESSAGE,
            -> {
                TriggerDecision.Handle(
                    sessionId = conversationId.toSessionId(),
                    seedHistory = true,
                    explicitMention = false,
                )
            }

            ChatTrigger.PASSIVE_MESSAGE -> {
                if (!conversationId.isThread) {
                    return TriggerDecision.Ignore
                }

                val sessionId = conversationId.toSessionId()
                if (!agentSessions.exists(sessionId)) {
                    return TriggerDecision.Ignore
                }

                TriggerDecision.Handle(
                    sessionId = sessionId,
                    seedHistory = false,
                    explicitMention = false,
                )
            }
        }
}

private fun ChatConversationId.sessionIdForInvitedMessage(messageId: String): SessionId =
    if (isThread) {
        toSessionId()
    } else {
        SessionId(channelId, messageId)
    }
