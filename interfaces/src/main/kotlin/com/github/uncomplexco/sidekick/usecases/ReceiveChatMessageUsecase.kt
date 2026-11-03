package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageQueue
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import org.springframework.stereotype.Component

@Component
class ReceiveChatMessageUsecase(
    private val queue: IncomingMessageQueue,
    private val turnCoordinator: TurnCoordinator,
) {
    suspend fun receive(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        queue.enqueue(conversationId, message, chat)
    }

    suspend fun receiveNow(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        turnCoordinator.run(
            TurnTrigger.Incoming(
                IncomingBatch(conversationId, listOf(message)),
                chat,
            )
        )
    }
}
