package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class IncomingMessageQueueDrainTest {
    @Nested
    inner class Ownership {
        @Test
        fun `empty steering drains retain the consumer and its handler`() = queueTest {
            // Arrange
            enqueue("first")
            val first = next()

            // Act
            repeat(100) { assertEquals(emptyList(), first.inbox.drain()) }
            enqueue("second")
            enqueue("third")

            // Assert
            assertEquals(listOf("second", "third"), first.inbox.drainIds())
            assertTrue(started.tryReceive().isFailure)
            first.finish.complete(Unit)
        }

        @Test
        fun `pending messages wait for the active handler then form one next batch`() = queueTest {
            // Arrange
            enqueue("first")
            val first = next()
            enqueue("second")
            enqueue("third")

            // Act
            assertTrue(started.tryReceive().isFailure)
            first.finish.complete(Unit)
            val second = next()

            // Assert
            assertEquals(listOf("second", "third"), second.messages)
            assertFailsWith<IllegalStateException> { first.inbox.drain() }
            second.finish.complete(Unit)
        }

        @Test
        fun `another key progresses while the first handler is blocked`() = queueTest {
            // Arrange
            enqueue("first", "blocked")
            val blocked = next()

            // Act
            enqueue("second", "independent")
            val independent = next()

            // Assert
            assertEquals(listOf("second"), independent.messages)
            assertEquals("independent", independent.key)
            assertTrue(!blocked.finish.isCompleted)
            independent.finish.complete(Unit)
            blocked.finish.complete(Unit)
        }

        @Test
        fun `enqueues for an owned key retain its delivery context`() = queueTest {
            // Arrange
            enqueue("first")
            val first = next()

            // Act
            enqueue("second", chat = mock(ChatPlatformAdapter::class.java))
            first.finish.complete(Unit)
            val second = next()

            // Assert
            assertEquals(listOf("second"), second.messages)
            assertSame(first.chat, second.chat)
            second.finish.complete(Unit)
        }

        @Test
        fun `idle key can be acquired with new delivery context and old inbox remains expired`() =
            queueTest {
                // Arrange
                enqueue("first")
                val first = next()
                first.finish.complete(Unit)
                job.children.toList().forEach { it.join() }
                val newChat = mock(ChatPlatformAdapter::class.java)

                // Act
                enqueue("new", chat = newChat)
                val second = next()
                enqueue("pending")

                // Assert
                assertEquals(listOf("new"), second.messages)
                assertSame(newChat, second.chat)
                assertFailsWith<IllegalStateException> { first.inbox.drain() }
                assertEquals(listOf("pending"), second.inbox.drainIds())
                second.finish.complete(Unit)
                job.children.toList().forEach { it.join() }
            }
    }

    @Nested
    inner class MessageAccounting {
        @Test
        fun `successive steering drains consume ordered distinct groups and leave later input queued`() =
            queueTest {
                // Arrange
                enqueue("first")
                val first = next()
                enqueue("second")
                enqueue("third")

                // Act
                val firstDrain = first.inbox.drainIds()
                enqueue("fourth")
                val secondDrain = first.inbox.drainIds()
                val emptyDrain = first.inbox.drain()
                enqueue("fifth")
                first.finish.complete(Unit)
                val second = next()

                // Assert
                assertEquals(listOf("first"), first.messages)
                assertEquals(listOf("second", "third"), firstDrain)
                assertEquals(listOf("fourth"), secondDrain)
                assertEquals(emptyList(), emptyDrain)
                assertEquals(listOf("fifth"), second.messages)
                second.finish.complete(Unit)
            }

        @Test
        fun `steering cannot consume another key's messages`() = queueTest {
            // Arrange
            enqueue("a1", "a")
            val a = next()
            enqueue("b1", "b")
            val b = next()
            enqueue("a2", "a")
            enqueue("b2", "b")

            // Act / Assert
            assertEquals(listOf("a2"), a.inbox.drainIds())
            assertEquals(listOf("b2"), b.inbox.drainIds())
            a.finish.complete(Unit)
            b.finish.complete(Unit)
        }

        @Test
        fun `expired inbox cannot steal messages from the next turn`() = queueTest {
            // Arrange
            enqueue("first")
            val first = next()
            enqueue("second")
            first.finish.complete(Unit)
            val second = next()
            enqueue("third")

            // Act / Assert
            assertFailsWith<IllegalStateException> { first.inbox.drain() }
            assertEquals(listOf("third"), second.inbox.drainIds())
            second.finish.complete(Unit)
        }
    }

    @Nested
    inner class ConcurrentHandoff {
        @RepeatedTest(10)
        fun `enqueue racing with handler return never loses input or overlaps handlers`() =
            queueTest {
                // Arrange
                enqueue("seed")
                var current = next()

                repeat(300) { index ->
                    val previous = current
                    val race = CompletableDeferred<Unit>()

                    // Act
                    coroutineScope {
                        launch(Dispatchers.Default) {
                            race.await()
                            previous.finish.complete(Unit)
                        }
                        launch(Dispatchers.Default) {
                            race.await()
                            enqueue("message-$index")
                        }
                        race.complete(Unit)
                    }
                    current = next()

                    // Assert
                    assertEquals(listOf("message-$index"), current.messages)
                    assertFailsWith<IllegalStateException> { previous.inbox.drain() }
                }
                current.finish.complete(Unit)
            }

        @RepeatedTest(10)
        fun `concurrent producers and steering drains consume every message exactly once`() =
            queueTest {
                // Arrange
                enqueue("seed")
                val first = next()
                val consumed = mutableListOf<String>()
                val expected = (1..8).flatMap { producer -> (1..100).map { "$producer-$it" } }
                val race = CompletableDeferred<Unit>()

                // Act
                coroutineScope {
                    val producers =
                        (1..8).map { producer ->
                            launch(Dispatchers.Default) {
                                race.await()
                                repeat(100) { enqueue("$producer-${it + 1}") }
                            }
                        }
                    val steering =
                        launch(Dispatchers.Default) {
                            race.await()
                            repeat(300) { consumed += first.inbox.drainIds() }
                        }
                    race.complete(Unit)
                    producers.forEach { it.join() }
                    steering.join()
                }
                consumed += first.inbox.drainIds()

                // Assert
                assertEquals(expected.sorted(), consumed.sorted())
                (1..8).forEach { producer ->
                    assertEquals(
                        (1..100).map { "$producer-$it" },
                        consumed.filter { it.startsWith("$producer-") },
                    )
                }
                assertTrue(started.tryReceive().isFailure)
                first.finish.complete(Unit)
            }
    }

    @Nested
    inner class FailureAndCancellation {
        @Test
        fun `handler failure expires its inbox and continues queued input without replay`() =
            queueTest {
                // Arrange
                enqueue("first")
                val first = next()
                enqueue("consumed")
                assertEquals(listOf("consumed"), first.inbox.drainIds())
                enqueue("pending")

                // Act
                first.finish.completeExceptionally(IllegalStateException("handler failed"))
                val second = next()

                // Assert
                assertEquals(listOf("pending"), second.messages)
                assertFailsWith<IllegalStateException> { first.inbox.drain() }
                second.finish.complete(Unit)
            }

        @Test
        fun `scope cancellation expires inboxes and does not run waiting batches`() = queueTest {
            // Arrange
            enqueue("first")
            val first = next()
            enqueue("pending")

            // Act
            job.cancelAndJoin()

            // Assert
            assertFailsWith<IllegalStateException> { first.inbox.drain() }
            assertTrue(started.tryReceive().isFailure)
            assertEquals(0, active.getValue("key").get())
        }
    }

    private fun queueTest(block: suspend Probe.() -> Unit) = runBlocking {
        withTimeout(15_000) {
            val probe = Probe()
            try {
                probe.block()
            } finally {
                probe.job.cancelAndJoin()
            }
            assertEquals(0, probe.overlaps.get(), "Handlers for the same key must never overlap")
        }
    }

    private class Probe {
        val job = SupervisorJob()
        val started = Channel<Invocation>(Channel.UNLIMITED)
        val active = ConcurrentHashMap<String, AtomicInteger>()
        val overlaps = AtomicInteger()
        private val chat = mock(ChatPlatformAdapter::class.java)
        private val queue =
            IncomingMessageQueue(SidekickCoroutineScope(CoroutineScope(job))) { batch, chat, inbox
                ->
                val key = batch.conversationId.threadId!!
                val count = active.computeIfAbsent(key) { AtomicInteger() }
                if (count.incrementAndGet() != 1) overlaps.incrementAndGet()
                val invocation = Invocation(key, batch.messages.map { it.id }, chat, inbox)
                try {
                    started.send(invocation)
                    invocation.finish.await()
                } finally {
                    count.decrementAndGet()
                }
            }

        suspend fun enqueue(
            value: String,
            key: String = "key",
            chat: ChatPlatformAdapter = this.chat,
        ) {
            queue.enqueue(
                ChatConversationId("C123", key),
                IncomingMessage(
                    value,
                    1,
                    MessageAuthor("user", "User"),
                    "hello",
                    IncomingMessageType.PASSIVE_MESSAGE,
                ),
                chat,
            )
        }

        suspend fun next(): Invocation = started.receive()
    }

    private class Invocation(
        val key: String,
        val messages: List<String>,
        val chat: ChatPlatformAdapter,
        val inbox: SteeringInbox,
    ) {
        val finish = CompletableDeferred<Unit>()
    }

    companion object {
        private fun SteeringInbox.drainIds(): List<String> = drain().map { it.id }
    }
}
