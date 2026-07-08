package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.InboundMessagesQueue
import com.github.uncomplexco.sidekick.application.turn.TurnExecutor
import org.springframework.stereotype.Component

@Component
class HandleIncomingChatMessageUsecase(
    private val agentConfig: AgentConfig,
    private val queue: InboundMessagesQueue,
    private val turnExecutor: TurnExecutor,
) {
    suspend fun handle(
        conversationId: ChatConversationId,
        message: InboundMessage,
        chat: ChatPlatformAdapter,
    ) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }

        queue.enqueue(conversationId, message, chat)
    }

    suspend fun handleNow(
        conversationId: ChatConversationId,
        message: InboundMessage,
        chat: ChatPlatformAdapter,
    ) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }

        turnExecutor.run(conversationId, listOf(message), chat)
    }
}
