package com.github.uncomplexco.sidekick.application.turn.checkpoints

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import ai.koog.agents.snapshot.feature.tombstoneCheckpoint
import ai.koog.serialization.JSONPrimitive
import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationStats
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TurnCheckpointPersistenceTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `persists latest checkpoint and clears completed turn`() =
        runBlocking {
            // Arrange
            val conversationId = ConversationId("C123", "1700000000.000")
            val sessionId = conversationId.lockKey()
            val provider = provider("turn-1")
            val first = checkpoint("checkpoint-1", 1)
            val latest = checkpoint("checkpoint-2", 2)
            val state = stateStore().load(conversationId)
            state.stats = ConversationStats(totalTokens = 123, messages = 4)
            stateStore().save(conversationId, state)

            // Act
            provider.saveCheckpoint(sessionId, first)
            provider.saveCheckpoint(sessionId, latest)
            val loaded = provider("turn-1").getLatestCheckpoint(sessionId)
            val updatedState = stateStore().load(conversationId)
            updatedState.stats = updatedState.stats.copy(messages = 5)
            stateStore().save(conversationId, updatedState)

            // Assert
            assertEquals(latest, loaded)
            assertEquals(listOf(latest), provider("turn-1").getCheckpoints(sessionId))
            assertEquals(123, stateStore().load(conversationId).stats.totalTokens)
            assertEquals(5, stateStore().load(conversationId).stats.messages)
            assertEquals("turn-1", stateStore().loadActiveTurn(conversationId)?.id)
            assertEquals(latest, stateStore().loadActiveTurn(conversationId)?.checkpoint)
            assertNull(provider("turn-2").getLatestCheckpoint(sessionId))

            val path = dir.resolve("state/slack/channels/C123/threads/1700000000.000/runtime.json")
            assertEquals(true, Files.readAllLines(path).size > 1)
            assertEquals(true, Files.readString(path).contains("\"checkpoint\""))

            // Act
            provider.saveCheckpoint(
                sessionId,
                tombstoneCheckpoint(Instant.parse("2026-01-01T00:00:03Z"), version = 3),
            )

            // Assert
            assertNull(provider.getLatestCheckpoint(sessionId))
            assertNull(stateStore().loadActiveTurn(conversationId))
            assertEquals(123, stateStore().load(conversationId).stats.totalTokens)
        }

    private fun provider(turnId: String) = TurnCheckpointPersistence(stateStore()).forTurn(turnId)

    private fun stateStore() = FilesystemConversationStateStore(config())

    private fun config() = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())

    private fun checkpoint(
        id: String,
        version: Long,
    ) = AgentCheckpointData(
        checkpointId = id,
        createdAt = Instant.parse("2026-01-01T00:00:0${version}Z"),
        messageHistory = emptyList(),
        version = version,
        graphProperties =
            GraphCheckpointProperties(
                nodePath = "session/strategy/node-$version",
                lastOutput = JSONPrimitive("output-$version"),
            ),
    )
}
