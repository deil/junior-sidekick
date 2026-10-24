package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

sealed interface SteeringInput {
    data class Messages(val messages: List<IncomingMessage>) : SteeringInput

    data object StopRequested : SteeringInput
}

/** Valid only during its batch handler. An empty drain never releases consumer ownership. */
fun interface SteeringInbox {
    fun drain(): SteeringInput

    fun stopRequested(): Boolean = false
}

@Component
class IncomingMessageQueue
internal constructor(
    private val scope: SidekickCoroutineScope,
    private val handleBatch: suspend (IncomingBatch, ChatPlatformAdapter, SteeringInbox) -> Unit,
) {
    @Autowired
    constructor(
        scope: SidekickCoroutineScope,
        turnCoordinator: TurnCoordinator,
    ) : this(
        scope,
        { batch, chat, inbox -> turnCoordinator.run(TurnTrigger.Incoming(batch, chat), inbox) },
    )

    private val queue = mutableMapOf<BatchKey, Entry>()

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
            if (
                queue.any { (owned, entry) ->
                    entry.stopRequested &&
                        owned.matches(
                            key.conversationId.channelId,
                            key.conversationId.platform,
                            key.threadId,
                        )
                }
            )
                return
            if (queue.containsKey(key)) {
                queue[key]!!.pending.add(message)
            } else {
                val entry = Entry(chat, message)
                queue[key] = entry
                startQueueConsumer(key, entry)
            }
        }
    }

    fun requestStop(conversationId: ChatConversationId) {
        synchronized(queue) {
            queue
                .filterKeys {
                    it.matches(
                        conversationId.channelId,
                        conversationId.platform,
                        conversationId.threadId,
                    )
                }
                .forEach { (key, entry) ->
                    entry.stopRequested = true
                    entry.pending.clear()
                    log.info("{} stop requested", key.conversationId.logLabel())
                }
        }
    }

    // Application failures must not strand the messages waiting behind the failed turn.
    @Suppress("TooGenericExceptionCaught")
    private fun startQueueConsumer(key: BatchKey, entry: Entry) {
        log.info("{} acquired queue consumer", key.conversationId.logLabel())
        scope
            .launch(Dispatchers.Default) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val messages =
                        synchronized(queue) {
                            if (entry.pending.isEmpty()) {
                                queue.remove(key)
                                log.info(
                                    "{} released queue consumer",
                                    key.conversationId.logLabel(),
                                )
                                return@launch
                            }
                            takePending(entry)
                        }
                    var active = true
                    val inbox =
                        object : SteeringInbox {
                            override fun stopRequested(): Boolean =
                                synchronized(queue) {
                                    check(active) { "Steering inbox is no longer active" }
                                    entry.stopRequested
                                }

                            override fun drain(): SteeringInput =
                                synchronized(queue) {
                                    check(active) { "Steering inbox is no longer active" }
                                    if (entry.stopRequested) SteeringInput.StopRequested
                                    else SteeringInput.Messages(takePending(entry))
                                }
                        }

                    try {
                        currentCoroutineContext().ensureActive()
                        handleBatch(IncomingBatch(key.conversationId, messages), entry.chat, inbox)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        log.error(
                            "{} failed to process batch",
                            key.conversationId.logLabel(),
                            error,
                        )
                    } finally {
                        synchronized(queue) { active = false }
                    }
                }
            }
            .invokeOnCompletion {
                synchronized(queue) {
                    if (queue.remove(key, entry)) {
                        log.info("{} queue consumer stopped", key.conversationId.logLabel())
                    }
                }
            }
    }

    private fun takePending(entry: Entry): List<IncomingMessage> =
        entry.pending.toList().also { entry.pending.clear() }

    private fun BatchKey.matches(
        channelId: String,
        platform: ChatPlatform,
        threadId: String?,
    ): Boolean =
        conversationId.platform == platform &&
            conversationId.channelId == channelId &&
            this.threadId == threadId

    private class Entry(val chat: ChatPlatformAdapter, message: IncomingMessage) {
        val pending = mutableListOf(message)
        var stopRequested = false
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
