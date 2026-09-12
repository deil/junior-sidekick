package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import org.springframework.stereotype.Component

sealed interface TurnAdmissionDecision {
    data class Admitted(
        val conversationId: ConversationId,
        val seedHistory: Boolean,
        val explicitMention: Boolean,
    ) : TurnAdmissionDecision

    data object Ignore : TurnAdmissionDecision
}

@Component
class TurnAdmission(private val sessions: SessionManager) {
    fun admit(batch: IncomingBatch): TurnAdmissionDecision {
        val candidates = batch.messages.mapNotNull { admitOne(batch.conversationId, it) }

        if (candidates.isEmpty()) {
            return TurnAdmissionDecision.Ignore
        }

        val conversationIds = candidates.map { it.conversationId }.distinct()
        require(conversationIds.size == 1) {
            "Inbound batch resolved to multiple conversations: $conversationIds"
        }

        return TurnAdmissionDecision.Admitted(
            conversationId = candidates.first().conversationId,
            seedHistory = candidates.any { it.seedHistory },
            explicitMention = candidates.any { it.explicitMention },
        )
    }

    private fun admitOne(
        chatConversationId: ChatConversationId,
        message: IncomingMessage,
    ): Candidate? {
        return when (message.type) {
            IncomingMessageType.EXPLICIT_MENTION ->
                Candidate(
                    conversationId = threadOrParent(chatConversationId, message.id),
                    seedHistory = chatConversationId.isThread,
                    explicitMention = true,
                )

            IncomingMessageType.ASSISTANT_MESSAGE ->
                Candidate(
                    conversationId = convert(chatConversationId),
                    seedHistory = true,
                    explicitMention = false,
                )

            IncomingMessageType.PASSIVE_MESSAGE -> {
                if (chatConversationId.isThread) {
                    val conversationId = convert(chatConversationId)
                    if (sessions.exists(conversationId) && sessions.isSubscribed(conversationId)) {
                        Candidate(
                            conversationId = conversationId,
                            seedHistory = false,
                            explicitMention = false,
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    private fun convert(id: ChatConversationId): ConversationId =
        ConversationId(
            channelId = id.channelId,
            threadId =
                id.threadId
                    ?: if (id.isDM) "" else error("A channel continuation requires a thread id"),
        )

    private fun threadOrParent(
        id: ChatConversationId,
        messageId: String,
    ): ConversationId =
        if (id.isThread) {
            convert(id)
        } else {
            ConversationId(id.channelId, messageId)
        }

    private data class Candidate(
        val conversationId: ConversationId,
        val seedHistory: Boolean,
        val explicitMention: Boolean,
    )
}
