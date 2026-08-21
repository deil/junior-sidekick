package com.github.uncomplexco.sidekick.application.stats

import com.github.uncomplexco.sidekick.application.conversation.ActiveTurn
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class WeeklyStatsServiceTest {
    @Test
    fun `aggregates conversations started during the last seven days`() {
        // Arrange
        val executedAt = Instant.parse("2026-08-14T09:00:00Z")
        val cutoff = executedAt.minus(Duration.ofDays(7))
        val store = TestConversationStateStore()
        store.add(cutoff, usage("C1", 100, 20, "U1", "U2"))
        store.add(executedAt.minusSeconds(1), usage("C1", 200, 30, "U2", "U3"))
        store.add(executedAt.minus(Duration.ofDays(8)), usage("D1", 1_000, 500, "U4"))
        store.add(executedAt, usage("G1", 1_000, 500, "U5"))

        // Act
        val stats = WeeklyStatsService(store).gather(executedAt)

        // Assert
        assertEquals(1, stats.projects)
        assertEquals(2, stats.conversations)
        assertEquals(350, stats.tokensConsumed)
        assertEquals(3, stats.users)
    }

    private fun usage(
        channelId: String,
        inputTokens: Long,
        outputTokens: Long,
        vararg userIds: String,
    ) = ConversationUsage(channelId, userIds.toSet(), inputTokens, outputTokens)

    private class TestConversationStateStore : ConversationStateStore {
        private val conversations = mutableListOf<Pair<Long, ConversationUsage>>()

        fun add(
            startedAt: Instant,
            usage: ConversationUsage,
        ) {
            conversations += startedAt.toEpochMilli() to usage
        }

        override fun exists(id: ConversationId): Boolean = false

        override fun load(id: ConversationId): ConversationState = error("Not used")

        override fun loadUsageStartedBetween(
            startInclusiveMs: Long,
            endExclusiveMs: Long,
        ): List<ConversationUsage> =
            conversations
                .filter { (startedAtMs) -> startedAtMs in startInclusiveMs..<endExclusiveMs }
                .map { it.second }

        override fun save(
            id: ConversationId,
            state: ConversationState,
        ) = error("Not used")

        override suspend fun loadActiveTurn(id: ConversationId): ActiveTurn? = error("Not used")

        override suspend fun saveActiveTurn(
            id: ConversationId,
            activeTurn: ActiveTurn?,
        ) = error("Not used")

        override suspend fun <T> withSessionLock(
            id: ConversationId,
            block: suspend () -> T,
        ): T = error("Not used")
    }
}
