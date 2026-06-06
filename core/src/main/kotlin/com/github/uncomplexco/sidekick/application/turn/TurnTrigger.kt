package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import org.springframework.stereotype.Component

sealed interface TurnTriggerDecision {
    data class ShouldHandle(
        val conversationId: ConversationId,
        val seedHistory: Boolean,
        val explicitMention: Boolean,
    ) : TurnTriggerDecision

    data object Ignore : TurnTriggerDecision
}

@Component
class InboundMessageFilter(
    private val conversations: SessionManager,
) {
    fun shouldTriggerTurn(
        chatConversationId: ChatConversationId,
        messages: List<InboundMessage>,
    ): TurnTriggerDecision {
        val handles =
            messages
                .map { message ->
                    shouldTriggerTurn(
                        chatConversationId = chatConversationId,
                        trigger = message.type,
                        messageId = message.id,
                    )
                }.filterIsInstance<TurnTriggerDecision.ShouldHandle>()

        if (handles.isEmpty()) {
            return TurnTriggerDecision.Ignore
        }

        val conversationIds = handles.map { it.conversationId }.distinct()
        require(conversationIds.size == 1) {
            "Inbound batch resolved to multiple conversations: $conversationIds"
        }

        return TurnTriggerDecision.ShouldHandle(
            conversationId = handles.first().conversationId,
            seedHistory = handles.any { it.seedHistory },
            explicitMention = handles.any { it.explicitMention },
        )
    }

    fun shouldTriggerTurn(
        chatConversationId: ChatConversationId,
        trigger: ChatMessageType,
        messageId: String,
    ): TurnTriggerDecision =
        when (trigger) {
            ChatMessageType.EXPLICIT_MENTION -> {
                TurnTriggerDecision.ShouldHandle(
                    conversationId = threadOrParent(chatConversationId, messageId),
                    seedHistory = chatConversationId.isThread,
                    explicitMention = true,
                )
            }

            ChatMessageType.ASSISTANT_MESSAGE -> {
                TurnTriggerDecision.ShouldHandle(
                    conversationId = convert(chatConversationId),
                    seedHistory = true,
                    explicitMention = false,
                )
            }

            ChatMessageType.PASSIVE_MESSAGE -> {
                if (!chatConversationId.isThread) {
                    return TurnTriggerDecision.Ignore
                }

                val conversationId = convert(chatConversationId)
                if (!conversations.exists(conversationId)) {
                    return TurnTriggerDecision.Ignore
                }

                TurnTriggerDecision.ShouldHandle(
                    conversationId = conversationId,
                    seedHistory = false,
                    explicitMention = false,
                )
            }
        }

    private fun convert(id: ChatConversationId) = ConversationId(id.channelId, id.threadId!!)

    private fun threadOrParent(
        id: ChatConversationId,
        messageId: String,
    ): ConversationId =
        if (id.isThread) {
            convert(id)
        } else {
            ConversationId(id.channelId, messageId)
        }
}
