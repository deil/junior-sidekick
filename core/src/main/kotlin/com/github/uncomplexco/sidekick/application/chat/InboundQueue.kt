package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.turn.TurnExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

@Component
class InboundMessagesQueue(
    private val scope: CoroutineScope,
    private val turnExecutor: TurnExecutor,
) : DisposableBean {
    private val queue = HashMap<ChatConversationId, MutableList<InboundMessage>>()

    suspend fun enqueue(
        conversationId: ChatConversationId,
        message: InboundMessage,
        chat: ChatPlatformAdapter,
    ) {
        log.debug("{} @{}: {}", conversationId.logLabel(), message.sender.username, message.text)

        synchronized(queue) {
            if (queue.containsKey(conversationId)) {
                queue[conversationId]!!.add(message)
            } else {
                queue[conversationId] = mutableListOf(message)
                startQueueConsumer(conversationId, chat)
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }

    private fun startQueueConsumer(
        conversationId: ChatConversationId,
        chat: ChatPlatformAdapter,
    ) = scope.launch(Dispatchers.Default) {
        log.info("${conversationId.logLabel()} startMessagesConsumer()")
        while (true) {
            val messages = drain(conversationId) ?: break

            try {
                turnExecutor.run(conversationId, messages, chat)
            } catch (e: Exception) {
                log.error("${conversationId.logLabel()}: error processing messages: ${e.message}", e)
            }
        }

        log.info("${conversationId.logLabel()}: startMessagesConsumer() exited")
    }

    private fun drain(conversationId: ChatConversationId) =
        synchronized(queue) {
            val messages = queue[conversationId] ?: return@synchronized null
            if (messages.isEmpty()) {
                log.info("${conversationId.logLabel()}: messages queue empty")
                queue.remove(conversationId)
                return@synchronized null
            }

            return@synchronized mutableListOf<InboundMessage>().also {
                it.addAll(messages)
                messages.clear()
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger("sidekick.session-messages-queue")
    }
}
