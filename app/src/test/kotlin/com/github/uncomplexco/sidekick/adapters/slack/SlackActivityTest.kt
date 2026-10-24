package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageQueue
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.DeterministicParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.LlmParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.TurnAdmission
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnParticipation
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import com.github.uncomplexco.sidekick.usecases.ReceiveChatMessageUsecase
import com.slack.api.app_backend.events.payload.AgentSessionStoppedPayload
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.model.event.AgentSessionStoppedEvent
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SlackActivityTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `native stop event acknowledges immediately but notifies user only after work stops`() =
        runTest {
            // Arrange
            Fixture(this, dir).use { fixture ->
                fixture.receive(1)
                awaitExternal(fixture.agentStarted)
                runCurrent()
                fixture.receive(2)

                // Act
                val ack = fixture.requestStop()
                fixture.receive(3)

                // Assert
                assertEquals(200, ack.statusCode)
                assertEquals(emptyList(), fixture.slack.replies)
                assertEquals("processing", fixture.activity())
                advanceTimeBy(8_000)
                runCurrent()
                assertEquals("processing", fixture.slack.calls.last().request.status)
                fixture.finishAgent.complete(Unit)
                awaitExternal(fixture.slack.replied)
                awaitExternal(fixture.slack.cleared)
                assertNull(fixture.activity())
                assertEquals(
                    listOf("🫡 Stop received. Tools down."),
                    fixture.slack.replies.map { it.text },
                )
            }
        }

    @Test
    fun `activity remains visible beyond Slack timeout without new messages or tool completions`() =
        runTest {
            // Arrange
            Fixture(this, dir).use { fixture ->
                fixture.receive(1)
                awaitExternal(fixture.agentStarted)
                assertNotNull(fixture.activity(), "The initial turn must show activity")
                runCurrent()

                // Act
                advanceTimeBy(7_999)
                runCurrent()
                assertEquals(listOf(0L), fixture.slack.calls.map { it.time })
                advanceTimeBy(3_593_001)
                runCurrent()

                // Assert
                assertEquals(
                    (0L..3_600_000L step 8_000L).toList(),
                    fixture.slack.calls.map { it.time },
                )
                fixture.slack.calls.forEach {
                    assertEquals(CHANNEL, it.request.channelId)
                    assertEquals(THREAD, it.request.threadTs)
                    assertEquals("processing", it.request.status)
                }
                assertNotNull(
                    fixture.activity(),
                    "A still-running turn must renew activity before Slack expires it",
                )
            }
        }

    @Test
    fun `completed turn leaves no activity indicator`() = runTest {
        // Arrange
        Fixture(this, dir).use { fixture ->
            fixture.receive(1)
            awaitExternal(fixture.agentStarted)
            assertNotNull(fixture.activity())

            // Act
            fixture.finishAgent.complete(Unit)
            awaitExternal(fixture.slack.replied)
            awaitExternal(fixture.slack.cleared)
            advanceTimeBy(121_000)
            runCurrent()

            // Assert
            assertNull(fixture.activity(), "Completed work must not restart activity")
        }
    }

    @Test
    fun `failed agent clears activity and stops renewal`() = runTest {
        // Arrange
        Fixture(this, dir).use { fixture ->
            fixture.receive(1)
            awaitExternal(fixture.agentStarted)
            runCurrent()

            // Act
            fixture.finishAgent.completeExceptionally(IllegalStateException("Agent failed"))
            awaitExternal(fixture.slack.cleared)
            advanceTimeBy(121_000)
            runCurrent()

            // Assert
            assertNull(fixture.activity())
            assertEquals(
                listOf("processing", "active"),
                fixture.slack.calls.map { it.request.status },
            )
        }
    }

    @Test
    fun `cancelled turn clears activity and stops renewal`() = runTest {
        // Arrange
        Fixture(this, dir).use { fixture ->
            fixture.receive(1)
            awaitExternal(fixture.agentStarted)
            runCurrent()

            // Act
            fixture.close()
            awaitExternal(fixture.slack.cleared)
            advanceTimeBy(121_000)
            runCurrent()

            // Assert
            assertNull(fixture.activity())
            assertEquals("active", fixture.slack.calls.last().request.status)
        }
    }

    @Test
    fun `failed delivery clears activity and stops renewal`() = runTest {
        // Arrange
        Fixture(this, dir).use { fixture ->
            fixture.slack.failReplies = true
            fixture.receive(1)
            awaitExternal(fixture.agentStarted)
            runCurrent()

            // Act
            fixture.finishAgent.complete(Unit)
            awaitExternal(fixture.slack.cleared)
            advanceTimeBy(121_000)
            runCurrent()

            // Assert
            assertNull(fixture.activity())
            assertEquals(
                listOf("processing", "active"),
                fixture.slack.calls.map { it.request.status },
            )
        }
    }

    private class Fixture(private val test: TestScope, dir: Path) : AutoCloseable {
        val slack = SlackActivityApi { test.testScheduler.currentTime }
        val agentStarted = CompletableDeferred<Unit>()
        val finishAgent = CompletableDeferred<Unit>()
        private val scope = SidekickCoroutineScope(test.backgroundScope)
        private val config =
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
                it.botUsername = "UBOT"
            }
        private val paths = VirtualPathsFactory(config, ChatPlatform.SLACK)
        private val sessions =
            SessionManager(
                FilesystemConversationStateStore(config, ChatPlatform.SLACK),
                paths,
                SessionContextCompactor { _, _, _ -> error("No compaction expected") },
            )
        private val coordinator =
            TurnCoordinator(
                TurnAdmission(sessions),
                sessions,
                TurnParticipation(
                    config,
                    DeterministicParticipationClassifier(),
                    LlmParticipationClassifier(koogConfig()) { _, _ ->
                        error("No classification expected")
                    },
                ),
                AgentTurnExecutor { _, _, steer ->
                    agentStarted.complete(Unit)
                    finishAgent.await()
                    if (steer.stopRequested())
                        TurnExecutionResult.Stopped(AgentStats("normal", AgentUsageStats()))
                    else
                        TurnExecutionResult.Completed(
                            AgentResponse("Finished"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                },
                { SkillCatalog(emptyList()) },
            )
        private val queue = IncomingMessageQueue(scope, coordinator)
        private val incoming = ReceiveChatMessageUsecase(queue, coordinator)
        private val stopHandler = slackStopHandler(queue, HandledEventsDeduper())
        private val fileIngestor =
            SlackFileIngestor(
                "unused",
                paths,
                object : ImageSummarizer {
                    override suspend fun summarize(imagePath: Path): ImageSummarizer.Result =
                        error("No files expected")
                },
            )

        suspend fun receive(index: Int) {
            val context =
                object : EventContext() {
                        override fun client() = this@Fixture.slack.client
                    }
                    .also {
                        it.channelId = CHANNEL
                        it.botUserId = "UBOT"
                    }
            incoming.receive(
                ChatConversationId(CHANNEL, THREAD),
                IncomingMessage(
                    "m$index",
                    index.toLong(),
                    MessageAuthor("user", "User"),
                    "request $index",
                    IncomingMessageType.ASSISTANT_MESSAGE,
                ),
                SlackChatPlatformAdapter(
                    context,
                    ChatThreadId(THREAD, true),
                    { emptyList() },
                    fileIngestor,
                    scope,
                ),
            )
        }

        fun requestStop() =
            stopHandler.apply(
                AgentSessionStoppedPayload().also { payload ->
                    payload.event =
                        AgentSessionStoppedEvent().also {
                            it.channel = CHANNEL
                            it.threadTs = THREAD
                            it.eventTs = "1700000000.001"
                            it.user = "user"
                        }
                },
                EventContext(),
            )

        fun activity(): String? = slack.visibleStatus(CHANNEL, THREAD)

        override fun close() {
            val workers = test.backgroundScope.coroutineContext[Job]!!.children.toList()
            scope.close()
            test.testScheduler.runCurrent()
            runBlocking { withTimeout(5_000) { workers.forEach { it.join() } } }
        }
    }

    companion object {
        private const val CHANNEL = "D123"
        private const val THREAD = "1700000000.000"

        // Queue work runs on Default; do not auto-advance the activity clock while awaiting it.
        private fun awaitExternal(signal: CompletableDeferred<Unit>) = runBlocking {
            withTimeout(5_000) { signal.await() }
        }

        private fun koogConfig() =
            KoogConfig(
                openRouterApiKey = "unused",
                openRouterAppTitle = "Sidekick",
                openRouterAppUrl = "",
                fastModel = "test",
                fastProvider = "test",
                fastReasoningEffort = "low",
                defaultModel = "test",
                defaultProvider = "test",
                defaultReasoningEffort = "medium",
                ultrathinkModel = "test",
                ultrathinkProvider = "test",
                ultrathinkReasoningEffort = "high",
                imageModel = "test",
                maxAgentIterations = 10,
            )
    }
}
