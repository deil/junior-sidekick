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
    private val queue = HashMap<BatchKey, MutableList<InboundMessage>>()

    suspend fun enqueue(
        conversationId: ChatConversationId,
        message: InboundMessage,
        chat: ChatPlatformAdapter,
    ) {
        log.debug("{} @{}: {}", conversationId.logLabel(), message.sender.username, message.text)

        val key = batchKeyFor(conversationId, message)

        synchronized(queue) {
            if (queue.containsKey(key)) {
                queue[key]!!.add(message)
            } else {
                queue[key] = mutableListOf(message)
                startQueueConsumer(key, chat)
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }

    private fun startQueueConsumer(
        key: BatchKey,
        chat: ChatPlatformAdapter,
    ) = scope.launch(Dispatchers.Default) {
        log.info("${key.conversationId.logLabel()} startMessagesConsumer()")

        while (true) {
            val messages = drain(key) ?: break

            try {
                turnExecutor.run(key.conversationId, messages, chat)
            } catch (e: Exception) {
                log.error("${key.conversationId.logLabel()}: error processing messages: ${e.message}", e)
            }
        }

        log.info("${key.conversationId.logLabel()}: startMessagesConsumer() exited")
    }

    private fun drain(key: BatchKey) =
        synchronized(queue) {
            val messages = queue[key] ?: return@synchronized null
            if (messages.isEmpty()) {
                log.info("${key.conversationId.logLabel()}: messages queue empty")
                queue.remove(key)
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

internal fun batchKeyFor(
    chatConversationId: ChatConversationId,
    message: InboundMessage,
): BatchKey =
    if (chatConversationId.isThread) {
        BatchKey.Thread(chatConversationId)
    } else {
        BatchKey.Single(chatConversationId, message.id)
    }

internal sealed interface BatchKey {
    val conversationId: ChatConversationId
    val threadId: String

    data class Thread(
        override val conversationId: ChatConversationId,
    ) : BatchKey {
        override val threadId: String
            get() = conversationId.threadId!!
    }

    data class Single(
        override val conversationId: ChatConversationId,
        override val threadId: String,
    ) : BatchKey
}
