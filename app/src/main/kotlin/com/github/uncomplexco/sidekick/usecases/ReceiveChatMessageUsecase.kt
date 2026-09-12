package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
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
    private val agentConfig: AgentConfig,
    private val queue: IncomingMessageQueue,
    private val turnCoordinator: TurnCoordinator,
) {
    suspend fun receive(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        initializeBotUsername(chat)

        queue.enqueue(conversationId, message, chat)
    }

    suspend fun receiveNow(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        initializeBotUsername(chat)

        turnCoordinator.run(
            TurnTrigger.Incoming(
                IncomingBatch(conversationId, listOf(message)),
                chat,
            )
        )
    }

    private fun initializeBotUsername(chat: ChatPlatformAdapter) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }
    }
}
