package com.github.uncomplexco.sidekick.application.turn.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import org.springframework.stereotype.Component
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class ConversationStateChatHistoryProvider(
    private val store: ConversationStateStore,
) : ChatHistoryProvider {
    override suspend fun load(conversationId: String): List<Message> {
        val id = ConversationId.fromLockKey(conversationId)
        return store.withSessionLock(id) {
            store.load(id).koogMessages
        }
    }

    override suspend fun store(
        conversationId: String,
        messages: List<Message>,
    ) {
        val id = ConversationId.fromLockKey(conversationId)
        store.withSessionLock(id) {
            val state = store.load(id)
            state.koogMessages = messages.map { it.withIdIfMissing() }.toMutableList()
            store.save(id, state)
        }
    }

    private fun Message.withIdIfMissing(): Message =
        when (this) {
            is Message.Assistant -> if (id == null) copy(id = generateMessageId()) else this
            is Message.System -> if (id == null) copy(id = generateMessageId()) else this
            is Message.User -> if (id == null) copy(id = generateMessageId()) else this
        }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateMessageId(): String = "koog_msg_${Uuid.generateV7()}"
}
