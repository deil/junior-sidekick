package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageQueue
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.SteeringInbox
import com.github.uncomplexco.sidekick.application.chat.SteeringInput
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class TurnCoordinatorTest {
    @TempDir lateinit var dir: Path

    @Nested
    inner class CooperativeStop {
        @Test
        fun `user is notified of stopping only after running work finishes and activity ends`() =
            runBlocking {
                withTimeout(10_000) {
                    // Arrange
                    val config = config()
                    val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                    val sessions = conversationManager(config, store)
                    val started = CompletableDeferred<Unit>()
                    val finish = CompletableDeferred<Unit>()
                    val replies = mutableListOf<String>()
                    val events = mutableListOf<String>()
                    val chat = chat(replies, mutableListOf(), outputEvents = events)
                    val coordinator =
                        TurnCoordinator(
                            TurnAdmission(sessions),
                            sessions,
                            participation(config),
                            AgentTurnExecutor { _, _, _ ->
                                started.complete(Unit)
                                finish.await()
                                events += "work finished"
                                TurnExecutionResult.Stopped(AgentStats("normal", AgentUsageStats()))
                            },
                            { SkillCatalog(emptyList()) },
                        )
                    val execution = async {
                        coordinator.runIncoming(
                            ChatConversationId("C123", "thread"),
                            incoming(1),
                            chat,
                        )
                    }
                    started.await()

                    // Act
                    assertEquals(
                        emptyList(),
                        replies,
                        "Stop must not be announced while work is still running",
                    )
                    finish.complete(Unit)
                    execution.await()

                    // Assert
                    assertEquals(listOf("🫡 Stop received. Tools down."), replies)
                    assertEquals(
                        listOf(
                            "work finished",
                            "activity ended",
                            "notification: 🫡 Stop received. Tools down.",
                        ),
                        events,
                    )
                    val conversation = ConversationId("C123", "thread")
                    assertEquals(true, sessions.isSubscribed(conversation))
                    assertStoppedMessages(store.load(conversation).messages)
                }
            }

        @Test
        fun `stop returned by steering drain ends work without a normal reply`() = runBlocking {
            // Arrange
            val config = config()
            val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
            val sessions = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val coordinator =
                TurnCoordinator(
                    TurnAdmission(sessions),
                    sessions,
                    participation(config),
                    AgentTurnExecutor { _, _, steer ->
                        steer.drain()
                        if (steer.stopRequested())
                            TurnExecutionResult.Stopped(AgentStats("normal", AgentUsageStats()))
                        else successfulResult()
                    },
                    { SkillCatalog(emptyList()) },
                )

            // Act
            coordinator.run(
                TurnTrigger.Incoming(
                    IncomingBatch(ChatConversationId("C123", "thread"), listOf(incoming(1))),
                    chat(replies, mutableListOf()),
                ),
                SteeringInbox { SteeringInput.StopRequested },
            )

            // Assert
            assertEquals(listOf("🫡 Stop received. Tools down."), replies)
            assertStoppedMessages(store.load(ConversationId("C123", "thread")).messages)
        }

        @Test
        fun `completed answer is delivered without a stopped notification even when stop was requested`() =
            runBlocking {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val replies = mutableListOf<String>()
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        participation(config),
                        AgentTurnExecutor { _, _, _ -> successfulResult() },
                        { SkillCatalog(emptyList()) },
                    )
                val inbox =
                    object : SteeringInbox {
                        override fun stopRequested() = true

                        override fun drain(): SteeringInput =
                            error("A final answer must not drain steering input")
                    }

                // Act
                coordinator.run(
                    TurnTrigger.Incoming(
                        IncomingBatch(ChatConversationId("C123", "thread"), listOf(incoming(1))),
                        chat(replies, mutableListOf()),
                    ),
                    inbox,
                )

                // Assert
                assertEquals(listOf("done"), replies)
                assertEquals(
                    true,
                    store
                        .load(ConversationId("C123", "thread"))
                        .messages
                        .first { it.id == "m1" }
                        .replied,
                )
            }
    }

    private fun assertStoppedMessages(messages: List<SessionMessage>) {
        assertEquals(
            "USER_STOPPED",
            messages.single { it.role == SessionMessageRole.USER }.skippedReason,
        )
        assertEquals(SessionMessageRole.ASSISTANT, messages.last().role)
        assertEquals("🫡 Stop received. Tools down.", messages.last().text)
    }

    @Nested
    inner class Steering {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `steered inputs share the active turn outcome while input after the final drain starts another turn`(
            fails: Boolean
        ) = runBlocking {
            withTimeout(10_000) {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val started = Channel<RecordedTurn>(Channel.UNLIMITED)
                val updated = Channel<RecordedTurn>(Channel.UNLIMITED)
                val drain = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                val replies = mutableListOf<String>()
                val events = mutableListOf<Pair<String, String>>()
                val classifier = mock(LlmParticipationClassifier::class.java)
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        TurnParticipation(
                            config,
                            DeterministicParticipationClassifier(),
                            classifier,
                        ),
                        AgentTurnExecutor { turn, _, steer ->
                            started.send(turn)
                            if (turn.messages.first().id == "m1") {
                                drain.await()
                                updated.send(checkNotNull(steer.drain()))
                                finish.await()
                                if (fails) error("model failed after steering")
                            }
                            successfulResult()
                        },
                        { reviewSkills() },
                    )
                val chat =
                    chat(
                        replies,
                        mutableListOf(),
                        messageLifecycle = events,
                        onCompleted = { if (it.id == "m4") completed.complete(Unit) },
                    )
                SidekickCoroutineScope().use { scope ->
                    val queue = IncomingMessageQueue(scope, coordinator)
                    val conversation = ChatConversationId("C123", "thread")

                    // Act
                    queue.enqueue(conversation, incoming(1), chat)
                    val initial = started.receive()
                    steeringMessages().forEach { queue.enqueue(conversation, it, chat) }
                    drain.complete(Unit)
                    val steered = updated.receive()
                    assertSteeredInput(initial, steered)
                    queue.enqueue(conversation, incoming(4), chat)
                    finish.complete(Unit)
                    assertEquals(listOf("m4"), started.receive().messages.map { it.id })
                    completed.await()
                }

                // Assert
                assertSteeredOutcome(store, replies, events, fails)
                verifyNoInteractions(classifier)
            }
        }

        @Test
        fun `delivery failure removes processing reactions from initial and steered inputs`() =
            runBlocking {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val events = mutableListOf<Pair<String, String>>()
                val base = chat(mutableListOf(), mutableListOf(), messageLifecycle = events)
                val chat =
                    object : ChatPlatformAdapter by base {
                        override val delivery =
                            object : ResponseDelivery {
                                override suspend fun deliver(
                                    response: AgentResponse,
                                    metadata: ResponseMetadata,
                                ): PublishedMessage = error("delivery failed")
                            }
                    }
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        participation(config),
                        AgentTurnExecutor { _, _, steer ->
                            steer.drain()
                            successfulResult()
                        },
                        { SkillCatalog(emptyList()) },
                    )

                // Act
                assertFailsWith<IllegalStateException> {
                    coordinator.run(
                        TurnTrigger.Incoming(
                            IncomingBatch(
                                ChatConversationId("C123", "thread"),
                                listOf(incoming(1)),
                            ),
                            chat,
                        ),
                        SteeringInbox { SteeringInput.Messages(listOf(incoming(2))) },
                    )
                }

                // Assert
                assertEquals(
                    listOf(
                        "m1" to "processing",
                        "m2" to "processing",
                        "m1" to "failed",
                        "m2" to "failed",
                    ),
                    events,
                )
                val saved = store.load(ConversationId("C123", "thread"))
                assertEquals(listOf("m1", "m2"), saved.messages.map { it.id })
                assertEquals(listOf(null, null), saved.messages.map { it.replied })
            }

        @Test
        fun `successive steering drains extend the same turn and complete every input once`() =
            runBlocking {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val pending =
                    ArrayDeque(listOf(listOf(incoming(2)), listOf(incoming(3)), emptyList()))
                val turns = mutableListOf<RecordedTurn>()
                val replies = mutableListOf<String>()
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        participation(config),
                        AgentTurnExecutor { initial, _, steer ->
                            turns += initial
                            repeat(3) { steer.drain()?.let { turns += it } }
                            successfulResult()
                        },
                        { SkillCatalog(emptyList()) },
                    )

                // Act
                coordinator.run(
                    TurnTrigger.Incoming(
                        IncomingBatch(ChatConversationId("C123", "thread"), listOf(incoming(1))),
                        chat(replies, mutableListOf()),
                    ),
                    SteeringInbox { SteeringInput.Messages(pending.removeFirst()) },
                )

                // Assert
                assertEquals(
                    listOf(listOf("m1"), listOf("m1", "m2"), listOf("m1", "m2", "m3")),
                    turns.map { it.messages.map { message -> message.id } },
                )
                assertEquals(1, turns.map { it.context.turnId }.distinct().size)
                assertEquals(1, replies.size)
                assertEquals(
                    listOf(true, true, true),
                    store
                        .load(ConversationId("C123", "thread"))
                        .messages
                        .filter { it.role == SessionMessageRole.USER }
                        .map { it.replied },
                )
            }
    }

    private fun assertSteeredOutcome(
        store: FilesystemConversationStateStore,
        replies: List<String>,
        events: List<Pair<String, String>>,
        fails: Boolean,
    ) {
        val saved = store.load(ConversationId("C123", "thread"))
        val users = saved.messages.filter { it.role == SessionMessageRole.USER }
        assertEquals(listOf("m1", "m2", "m3", "m4"), users.map { it.id })
        assertEquals(listOf(!fails, !fails, !fails, true), users.map { it.replied })
        assertEquals(
            if (fails) listOf("AGENT_FAILURE", "AGENT_FAILURE", "AGENT_FAILURE", null)
            else List(4) { null },
            users.map { it.skippedReason },
        )
        val outcome = if (fails) "failed" else "completed"
        assertEquals(
            listOf(
                "m1" to "processing",
                "m2" to "processing",
                "m3" to "processing",
                "m1" to outcome,
                "m2" to outcome,
                "m3" to outcome,
                "m4" to "processing",
                "m4" to "completed",
            ),
            events,
        )
        assertEquals(2, replies.size)
        assertEquals(
            if (fails) 1 else 2,
            saved.messages.count { it.role == SessionMessageRole.ASSISTANT },
        )
        assertEquals(if (fails) 11L else 22L, saved.stats.consumedInputTokens)
        assertEquals(if (fails) 7L else 14L, saved.stats.consumedOutputTokens)
    }

    private fun reviewSkills() =
        SkillCatalog(
            listOf(
                Skill(
                    "review",
                    "Review changes",
                    dir.resolve("review"),
                    disableModelInvocation = false,
                    userInvocable = true,
                )
            )
        )

    private fun steeringMessages() =
        (2..3).map { index ->
            incoming(
                    index,
                    "/review ${if (index == 2) "What about latency?" else "Actually, staging"}",
                    IncomingMessageType.PASSIVE_MESSAGE,
                )
                .copy(
                    sender = MessageAuthor(if (index == 2) "U123" else "bob", "User"),
                    files =
                        (1..6).map { number ->
                            IncomingChatFile(
                                "f$index-$number",
                                "report.txt",
                                "text/plain",
                                "txt",
                                "https://files/report",
                                "https://chat/report",
                                "/data/$index-$number.txt",
                            )
                        },
                )
        }

    private fun assertSteeredInput(initial: RecordedTurn, steered: RecordedTurn) {
        assertEquals(initial.context.turnId, steered.context.turnId)
        assertEquals(listOf("m1", "m2", "m3"), steered.messages.map { it.id })
        assertEquals("m3", steered.context.lastMessageId)
        assertEquals(
            listOf("review", "review"),
            steered.messages.drop(1).map { it.explicitSkillInvocation?.skillName },
        )
        assertEquals(listOf(5, 5), steered.messages.drop(1).map { it.fileIds.size })
        assertEquals(10, steered.context.sessionFiles.size)
        assertEquals("bob", steered.messages.last().author?.username)
    }

    @Nested
    inner class BatchLifecycle {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `each responding input receives processing and its own completion or failure reaction`(
            fails: Boolean
        ) = runBlocking {
            // Arrange
            val config = config()
            val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
            val sessions = conversationManager(config, store)
            val events = mutableListOf<Pair<String, String>>()
            val seen = mutableListOf<List<String>>()
            val coordinator =
                TurnCoordinator(
                    TurnAdmission(sessions),
                    sessions,
                    participation(config),
                    AgentTurnExecutor { turn, _, _ ->
                        seen += turn.messages.map { it.id }
                        if (fails) error("model unavailable")
                        TurnExecutionResult.Completed(
                            AgentResponse("done"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                    { SkillCatalog(emptyList()) },
                )
            val messages =
                (1..2).map { index ->
                    IncomingMessage(
                        "m$index",
                        index.toLong(),
                        MessageAuthor("U123", "User"),
                        "help",
                        IncomingMessageType.EXPLICIT_MENTION,
                    )
                }

            // Act
            coordinator.run(
                TurnTrigger.Incoming(
                    IncomingBatch(ChatConversationId("C123", "thread"), messages),
                    chat(mutableListOf(), mutableListOf(), messageLifecycle = events),
                )
            )

            // Assert
            val outcome = if (fails) "failed" else "completed"
            assertEquals(
                listOf(
                    "m1" to "processing",
                    "m2" to "processing",
                    "m1" to outcome,
                    "m2" to outcome,
                ),
                events,
            )
            assertEquals(listOf(listOf("m1", "m2")), seen)
        }
    }

    @Nested
    inner class BatchOutcomes {
        @Test
        fun `file limit applies per message while one agent execution receives both sets of attachments`() =
            runBlocking {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val observed = mutableListOf<RecordedTurn>()
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        participation(config),
                        AgentTurnExecutor { turn, _, _ ->
                            observed += turn
                            successfulResult()
                        },
                        { SkillCatalog(emptyList()) },
                    )
                val messages =
                    (1..2).map { index ->
                        incoming(index)
                            .copy(
                                files =
                                    (1..6).map { number ->
                                        IncomingChatFile(
                                            "f$index-$number",
                                            "file.txt",
                                            "text/plain",
                                            "txt",
                                            "https://files.example/file",
                                            "https://chat.example/file",
                                            "/data/session/$index-$number.txt",
                                        )
                                    }
                            )
                    }

                // Act
                coordinator.run(
                    TurnTrigger.Incoming(
                        IncomingBatch(ChatConversationId("C123", "thread"), messages),
                        chat(mutableListOf(), mutableListOf()),
                    )
                )

                // Assert
                val turn = observed.single()
                assertEquals(listOf("m1", "m2"), turn.messages.map { it.id })
                assertEquals((1..5).map { "f1-$it" }, turn.messages[0].fileIds)
                assertEquals((1..5).map { "f2-$it" }, turn.messages[1].fileIds)
                assertEquals(10, turn.context.sessionFiles.size)
                assertEquals("m2", turn.context.lastMessageId)
                assertEquals(
                    2,
                    store.load(ConversationId("C123", "thread")).messages.count {
                        it.role == SessionMessageRole.USER && it.replied == true
                    },
                )
            }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `latest mention or unsubscribe determines one outcome for every batch input`(
            unsubscribeLast: Boolean
        ) = runBlocking {
            // Arrange
            val config = config()
            val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
            val sessions = conversationManager(config, store)
            val replies = mutableListOf<String>()
            val events = mutableListOf<Pair<String, String>>()
            val runs = mutableListOf<List<String>>()
            val coordinator =
                TurnCoordinator(
                    TurnAdmission(sessions),
                    sessions,
                    participation(config),
                    AgentTurnExecutor { turn, _, _ ->
                        runs += turn.messages.map { it.id }
                        TurnExecutionResult.Completed(
                            AgentResponse("done"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                    { SkillCatalog(emptyList()) },
                )
            val mention = incoming(if (unsubscribeLast) 1 else 2)
            val unsubscribe =
                incoming(
                    if (unsubscribeLast) 2 else 1,
                    "unsubscribe",
                    IncomingMessageType.PASSIVE_MESSAGE,
                )

            // Act
            coordinator.run(
                TurnTrigger.Incoming(
                    IncomingBatch(
                        ChatConversationId("C123", "thread"),
                        listOf(mention, unsubscribe),
                    ),
                    chat(replies, mutableListOf(), messageLifecycle = events),
                )
            )

            // Assert
            val saved = store.load(ConversationId("C123", "thread"))
            val users = saved.messages.filter { it.role == SessionMessageRole.USER }
            assertEquals(listOf("m1", "m2"), users.map { it.id })
            assertEquals(listOf(mention.id), users.filter { it.explicitMention }.map { it.id })
            assertEquals(!unsubscribeLast, saved.subscribed)
            assertEquals(listOf(!unsubscribeLast, !unsubscribeLast), users.map { it.replied })
            assertEquals(1, replies.size)
            assertEquals(if (unsubscribeLast) 0 else 1, runs.size)
            if (unsubscribeLast) {
                assertEquals(
                    listOf("UNSUBSCRIBE_COMMAND", "UNSUBSCRIBE_COMMAND"),
                    users.map { it.skippedReason },
                )
                assertEquals(emptyList(), events)
                assertEquals(0, saved.messages.count { it.role == SessionMessageRole.ASSISTANT })
            } else {
                assertEquals(listOf(listOf("m1", "m2")), runs)
                assertEquals(1, saved.messages.count { it.role == SessionMessageRole.ASSISTANT })
            }
        }

        @Test
        fun `all acknowledgment batch is recorded and skipped without a reply or reactions`() =
            runBlocking {
                // Arrange
                val config = config()
                val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                val sessions = conversationManager(config, store)
                val conversation = ConversationId("C123", "thread")
                sessions.recordIncomingMessages(
                    conversation,
                    false,
                    { emptyList() },
                    listOf(
                        com.github.uncomplexco.sidekick.application.conversation.SessionMessage(
                            "old",
                            SessionMessageRole.USER,
                            MessageAuthor("U123", "User"),
                            "existing",
                            createdAtMs = 0,
                        )
                    ),
                    emptyList(),
                )
                val replies = mutableListOf<String>()
                val events = mutableListOf<String>()
                val coordinator =
                    TurnCoordinator(
                        TurnAdmission(sessions),
                        sessions,
                        participation(config),
                        AgentTurnExecutor { _, _, _ -> error("Skipped batch must not execute") },
                        { SkillCatalog(emptyList()) },
                    )

                // Act
                coordinator.run(
                    TurnTrigger.Incoming(
                        IncomingBatch(
                            ChatConversationId("C123", "thread"),
                            listOf(
                                incoming(1, "thanks", IncomingMessageType.PASSIVE_MESSAGE),
                                incoming(2, "got it", IncomingMessageType.PASSIVE_MESSAGE),
                            ),
                        ),
                        chat(replies, events),
                    )
                )

                // Assert
                val users = store.load(conversation).messages.filter { it.id != "old" }
                assertEquals(listOf(false, false), users.map { it.replied })
                assertEquals(
                    listOf("ACKNOWLEDGMENT", "ACKNOWLEDGMENT"),
                    users.map { it.skippedReason },
                )
                assertEquals(emptyList(), replies)
                assertEquals(emptyList(), events)
            }
    }

    @Nested
    inner class QueueBatching {
        @Test
        fun `blocked conversation does not prevent another conversation from completing`() =
            runBlocking {
                withTimeout(10_000) {
                    // Arrange
                    val config = config()
                    val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                    val sessions = conversationManager(config, store)
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val otherCompleted = CompletableDeferred<Unit>()
                    val blockedCompleted = CompletableDeferred<Unit>()
                    val coordinator =
                        TurnCoordinator(
                            TurnAdmission(sessions),
                            sessions,
                            participation(config),
                            AgentTurnExecutor { turn, _, _ ->
                                if (
                                    turn.context.conversation.conversationId.threadId == "blocked"
                                ) {
                                    started.complete(Unit)
                                    release.await()
                                }
                                successfulResult()
                            },
                            { SkillCatalog(emptyList()) },
                        )
                    SidekickCoroutineScope().use { scope ->
                        val queue = IncomingMessageQueue(scope, coordinator)
                        val blocked =
                            chat(
                                mutableListOf(),
                                mutableListOf(),
                                onCompleted = { blockedCompleted.complete(Unit) },
                            )
                        val other =
                            chat(
                                mutableListOf(),
                                mutableListOf(),
                                onCompleted = { otherCompleted.complete(Unit) },
                            )

                        // Act
                        queue.enqueue(ChatConversationId("C123", "blocked"), incoming(1), blocked)
                        started.await()
                        queue.enqueue(ChatConversationId("C123", "other"), incoming(2), other)
                        otherCompleted.await()

                        // Assert
                        assertEquals(
                            true,
                            store
                                .load(ConversationId("C123", "other"))
                                .messages
                                .first { it.id == "m2" }
                                .replied,
                        )
                        assertEquals(
                            null,
                            store.load(ConversationId("C123", "blocked")).messages.single().replied,
                        )
                        release.complete(Unit)
                        blockedCompleted.await()
                    }
                }
            }

        @Test
        fun `queued messages form one next turn and messages arriving during it wait for the following turn`() =
            runBlocking {
                withTimeout(10_000) {
                    // Arrange
                    val config = config()
                    val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
                    val sessions = conversationManager(config, store)
                    val observed = Channel<List<String>>(Channel.UNLIMITED)
                    val proceed = Channel<Unit>(Channel.UNLIMITED)
                    val completed = CompletableDeferred<Unit>()
                    val replies = mutableListOf<String>()
                    val coordinator =
                        TurnCoordinator(
                            TurnAdmission(sessions),
                            sessions,
                            participation(config),
                            AgentTurnExecutor { turn, _, _ ->
                                observed.send(turn.messages.map { it.id })
                                proceed.receive()
                                successfulResult()
                            },
                            { SkillCatalog(emptyList()) },
                        )
                    val chat =
                        chat(
                            replies,
                            mutableListOf(),
                            onCompleted = { if (it.id == "m4") completed.complete(Unit) },
                        )
                    val conversation = ChatConversationId("C123", "thread")
                    SidekickCoroutineScope().use { scope ->
                        val queue = IncomingMessageQueue(scope, coordinator)

                        // Act / Assert
                        queue.enqueue(conversation, incoming(1), chat)
                        assertEquals(listOf("m1"), observed.receive())
                        queue.enqueue(conversation, incoming(2), chat)
                        queue.enqueue(conversation, incoming(3), chat)
                        proceed.send(Unit)
                        assertEquals(listOf("m2", "m3"), observed.receive())
                        queue.enqueue(conversation, incoming(4), chat)
                        proceed.send(Unit)
                        assertEquals(listOf("m4"), observed.receive())
                        proceed.send(Unit)
                        completed.await()
                    }
                    val saved = store.load(ConversationId("C123", "thread"))
                    assertEquals(3, replies.size)
                    assertEquals(
                        3,
                        saved.messages.count { it.role == SessionMessageRole.ASSISTANT },
                    )
                    assertEquals(
                        listOf(true, true, true, true),
                        saved.messages
                            .filter { it.role == SessionMessageRole.USER }
                            .map { it.replied },
                    )
                    assertEquals(33L, saved.stats.consumedInputTokens)
                    assertEquals(21L, saved.stats.consumedOutputTokens)
                }
            }
    }

    private fun incoming(
        index: Int,
        text: String = "help",
        type: IncomingMessageType = IncomingMessageType.EXPLICIT_MENTION,
    ) = IncomingMessage("m$index", index.toLong(), MessageAuthor("U123", "User"), text, type)

    private fun successfulResult() =
        TurnExecutionResult.Completed(
            AgentResponse("done"),
            AgentStats("normal", AgentUsageStats(inputTokenCount = 11, outputTokenCount = 7)),
        )

    @Test
    fun `scheduled turn runs directly in isolated session`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val seenConversationIds = mutableListOf<ConversationId>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { turn, _, _ ->
                        seenConversationIds += turn.context.conversation.conversationId
                        assertEquals("Run the report", turn.messages.single().text)
                        TurnExecutionResult.Completed(
                            AgentResponse("done"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val job = ScheduledJob(32, "report", null, "0 9 * * *", "UTC", "Run the report")
        val first = ConversationId("C123", "scheduled_32_first")
        val second = ConversationId("C123", "scheduled_32_second")

        // Act
        executor.run(
            TurnTrigger.Scheduled(
                first,
                job,
                chat(replies, mutableListOf()),
                startedAtMs = 1_000,
            )
        )
        executor.run(
            TurnTrigger.Scheduled(
                second,
                job,
                chat(replies, mutableListOf()),
                startedAtMs = 2_000,
            )
        )

        // Assert
        assertEquals(listOf(first, second), seenConversationIds)
        assertEquals(listOf("done", "done"), replies)
        assertEquals(2, store.load(first).messages.size)
        assertEquals(2, store.load(second).messages.size)
        assertEquals(
            "scheduled-job-32",
            store
                .load(first)
                .messages
                .single { it.role == SessionMessageRole.USER }
                .author
                ?.username,
        )
    }

    @Test
    fun `posts temporary failure reply and marks message skipped when agent fails`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        val statusLines = mutableListOf<String?>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { _, _, _ ->
                        error("OpenRouter API error: 429 Too Many Requests")
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val chat = chat(replies, lifecycle, statusLines)
        val message =
            IncomingMessage(
                id = "1700000000.000",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> help",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat)

        // Assert
        assertEquals(1, replies.size)
        assertEquals(":warning: OpenRouter API error: 429 Too Many Requests", replies.single())
        assertEquals(listOf<String?>("`[runtime failure]`"), statusLines)
        val state = store.load(ConversationId("C123", "1700000000.000"))
        val savedMessage = state.messages.single { it.id == message.id }
        assertEquals("AGENT_FAILURE", savedMessage.skippedReason)
        assertFalse(savedMessage.replied == true)
        assertEquals(emptyList(), state.messages.filter { it.role == SessionMessageRole.ASSISTANT })
        assertEquals(listOf("processing", "failed"), lifecycle)
    }

    @Test
    fun `delivers reply attachments and removes staged files`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val stagedFile = dir.resolve("staged/report.csv")
        Files.createDirectories(stagedFile.parent)
        Files.writeString(stagedFile, "report\n")
        val attachment =
            ReplyAttachment(stagedFile, "report.csv", "text/csv", Files.size(stagedFile))
        val delivered = mutableListOf<AgentResponse>()
        val deliveredStats = mutableListOf<TurnStats?>()
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent =
                    AgentTurnExecutor { _, _, _ ->
                        TurnExecutionResult.Completed(
                            response = AgentResponse("Attached the report.", listOf(attachment)),
                            stats =
                                AgentStats(
                                    profileName = "normal",
                                    usage =
                                        AgentUsageStats(
                                            inputTokenCount = 1_200,
                                            outputTokenCount = 345,
                                        ),
                                ),
                        )
                    },
                skills = { SkillCatalog(emptyList()) },
            )
        val chat =
            object : ChatPlatformAdapter {
                override val botUsername = "USIDEKICK"
                private val output =
                    object : TurnLifecycle, ResponseDelivery {
                        override fun begin() = Unit

                        override fun progress(text: String?) = Unit

                        override fun end() = Unit

                        override suspend fun markProcessing(message: IncomingMessage) {
                            lifecycle += "processing"
                        }

                        override suspend fun deliver(
                            response: AgentResponse,
                            metadata: ResponseMetadata,
                        ): PublishedMessage {
                            delivered += response
                            deliveredStats += (metadata as? ResponseMetadata.Turn)?.stats
                            return PublishedMessage("reply", 1)
                        }

                        override suspend fun markCompleted(message: IncomingMessage) {
                            lifecycle += "completed"
                        }

                        override suspend fun markFailed(message: IncomingMessage) {
                            lifecycle += "failed"
                        }
                    }
                override val lifecycle: TurnLifecycle = output
                override val delivery: ResponseDelivery = output

                override suspend fun loadHistory(
                    conversationId: ConversationId
                ): List<ChatMessage> = emptyList()

                override suspend fun ingestFiles(
                    conversationId: ConversationId,
                    files: List<IncomingChatFile>,
                ): List<IncomingChatFile> = files
            }
        val message =
            IncomingMessage(
                id = "1700000000.000",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> send the report",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat)

        // Assert
        assertEquals(listOf(attachment), delivered.single().attachments)
        assertEquals(1_200, deliveredStats.single()?.inputTokenCount)
        assertEquals(345, deliveredStats.single()?.outputTokenCount)
        assertFalse(Files.exists(stagedFile))
        val state = store.load(ConversationId("C123", "1700000000.000"))
        assertEquals(1_200, state.stats.consumedInputTokens)
        assertEquals(345, state.stats.consumedOutputTokens)
        assertEquals(listOf("processing", "completed"), lifecycle)
        assertEquals(
            emptyList(),
            state.messages.single { it.role == SessionMessageRole.ASSISTANT }.fileIds,
        )
    }

    @Test
    fun `does not mark skipped messages as processing`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent = AgentTurnExecutor { _, _, _ -> error("agent should not run") },
                skills = { SkillCatalog(emptyList()) },
            )
        val message =
            IncomingMessage(
                id = "1700000000.001",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "thanks",
                type = IncomingMessageType.ASSISTANT_MESSAGE,
            )

        // Act
        executor.runIncoming(
            ChatConversationId("D123", "1700000000.000"),
            message,
            chat(mutableListOf(), lifecycle),
        )

        // Assert
        assertEquals(emptyList(), lifecycle)
    }

    @Test
    fun `posts unsubscribe notification without marking message as processing`() = runBlocking {
        // Arrange
        val config = config()
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val manager = conversationManager(config, store)
        val replies = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        val executor =
            TurnCoordinator(
                admission = TurnAdmission(manager),
                sessions = manager,
                participation = participation(config),
                agent = AgentTurnExecutor { _, _, _ -> error("agent should not run") },
                skills = { SkillCatalog(emptyList()) },
            )
        val message =
            IncomingMessage(
                id = "1700000000.002",
                createdAtMs = 1,
                sender = MessageAuthor(username = "U123", fullName = "User"),
                text = "<@USIDEKICK> unsubscribe",
                type = IncomingMessageType.EXPLICIT_MENTION,
            )

        // Act
        executor.runIncoming(ChatConversationId("C123"), message, chat(replies, lifecycle))

        // Assert
        assertEquals(listOf("Unsubscribed. Mention me to resume"), replies)
        assertEquals(emptyList(), lifecycle)
    }

    private fun conversationManager(
        config: AgentConfig,
        store: FilesystemConversationStateStore,
    ): SessionManager =
        SessionManager(
            store,
            VirtualPathsFactory(config, ChatPlatform.SLACK),
            SessionContextCompactor(
                summarizer = { _, _, messages -> "summary for ${messages.size} messages" }
            ),
        )

    private fun config(): AgentConfig =
        AgentConfig(
                "Sidekick",
                dir.resolve("state").toString(),
                dir.resolve("workspace").toString(),
            )
            .also { it.botUsername = "USIDEKICK" }

    private fun participation(config: AgentConfig): TurnParticipation =
        TurnParticipation(
            config,
            DeterministicParticipationClassifier(),
            LlmParticipationClassifier(koogConfig()) { _, _ -> error("classifier should not run") },
        )

    private fun koogConfig(): KoogConfig =
        KoogConfig(
            openRouterApiKey = "test-key",
            openRouterAppTitle = "Sidekick",
            openRouterAppUrl = "",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.5",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            imageModel = "image-model",
            maxAgentIterations = 50,
        )

    private fun chat(
        replies: MutableList<String>,
        lifecycle: MutableList<String>,
        statusLines: MutableList<String?> = mutableListOf(),
        messageLifecycle: MutableList<Pair<String, String>> = mutableListOf(),
        onCompleted: (IncomingMessage) -> Unit = {},
        outputEvents: MutableList<String>? = null,
    ): ChatPlatformAdapter =
        object : ChatPlatformAdapter {
            override val botUsername = "USIDEKICK"
            private val output =
                object : TurnLifecycle, ResponseDelivery {
                    override fun begin() = Unit

                    override fun progress(text: String?) = Unit

                    override fun end() {
                        outputEvents?.add("activity ended")
                    }

                    override suspend fun markProcessing(message: IncomingMessage) {
                        lifecycle += "processing"
                        messageLifecycle += message.id to "processing"
                    }

                    override suspend fun deliver(
                        response: AgentResponse,
                        metadata: ResponseMetadata,
                    ): PublishedMessage {
                        outputEvents?.add("notification: ${response.text}")
                        replies += response.text
                        statusLines +=
                            if (metadata == ResponseMetadata.RuntimeFailure) {
                                "`[runtime failure]`"
                            } else {
                                null
                            }
                        return PublishedMessage("reply-${replies.size}", replies.size.toLong())
                    }

                    override suspend fun markCompleted(message: IncomingMessage) {
                        lifecycle += "completed"
                        messageLifecycle += message.id to "completed"
                        onCompleted(message)
                    }

                    override suspend fun markFailed(message: IncomingMessage) {
                        lifecycle += "failed"
                        messageLifecycle += message.id to "failed"
                    }
                }
            override val lifecycle: TurnLifecycle = output
            override val delivery: ResponseDelivery = output

            override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
                emptyList()

            override suspend fun ingestFiles(
                conversationId: ConversationId,
                files: List<IncomingChatFile>,
            ): List<IncomingChatFile> = files
        }

    private suspend fun TurnCoordinator.runIncoming(
        conversationId: ChatConversationId,
        message: IncomingMessage,
        chat: ChatPlatformAdapter,
    ) {
        run(
            TurnTrigger.Incoming(
                IncomingBatch(
                    conversationId = conversationId,
                    messages = listOf(message),
                ),
                chat,
            )
        )
    }
}
