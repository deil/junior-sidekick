package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class IncomingMessageQueue(
    private val scope: SidekickCoroutineScope,
    private val turnCoordinator: TurnCoordinator,
) {
    private val queue = HashMap<BatchKey, MutableList<IncomingMessage>>()

    suspend fun enqueue(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        log.debug(
            "{} @{}: {}",
            conversationId.logLabel(),
            message.sender.username,
            message.text,
        )

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

    private fun startQueueConsumer(
        key: BatchKey,
        chat: ChatPlatformAdapter,
    ) =
        scope.launch(Dispatchers.Default) {
            log.info("${key.conversationId.logLabel()} startMessagesConsumer()")

            while (true) {
                val messages = drain(key) ?: break

                try {
                    turnCoordinator.run(
                        TurnTrigger.Incoming(
                            IncomingBatch(key.conversationId, messages),
                            chat,
                        )
                    )
                } catch (e: Exception) {
                    log.error(
                        "${key.conversationId.logLabel()}: error processing messages: ${e.message}",
                        e,
                    )
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

            return@synchronized mutableListOf<IncomingMessage>().also {
                it.addAll(messages)
                messages.clear()
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger("sidekick.session-messages-queue")
    }
}

internal fun batchKeyFor(
    conversationId: ChatConversationId,
    message: IncomingMessage,
): BatchKey =
    if (
        conversationId.isThread ||
            (conversationId.platform == ChatPlatform.DISCORD && conversationId.isDM)
    ) {
        BatchKey.Thread(conversationId)
    } else {
        BatchKey.Single(conversationId, message.id)
    }

internal sealed interface BatchKey {
    val conversationId: ChatConversationId
    val threadId: String

    data class Thread(override val conversationId: ChatConversationId) : BatchKey {
        override val threadId: String
            get() = conversationId.threadId.orEmpty()
    }

    data class Single(
        override val conversationId: ChatConversationId,
        override val threadId: String,
    ) : BatchKey
}
