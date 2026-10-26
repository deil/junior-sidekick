package com.github.uncomplexco.sidekick.application.stats

import com.github.uncomplexco.sidekick.application.conversation.ActiveTurn
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

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
        assertEquals(1, stats.channels)
        assertEquals(2, stats.conversations)
        assertEquals(350, stats.tokensConsumed)
        assertEquals(3, stats.users)
    }

    @Test
    fun `supports thirty days with the same aggregation and period boundaries`() {
        val now = Instant.parse("2026-08-14T09:00:00Z")
        val store = TestConversationStateStore()
        store.add(now.minus(Duration.ofDays(1)), usage("C1", 1, 2, "U1"))
        store.add(now.minus(Duration.ofDays(20)), usage("C2", 10, 20, "U2", "U3"))
        store.add(now.minus(Duration.ofDays(31)), usage("C3", 100, 200, "U4"))
        store.add(now, usage("C4", 1000, 2000, "U5"))
        val service = WeeklyStatsService(store)

        val weekly = service.gather(now)
        val monthly = service.gather(now, Duration.ofDays(30))

        assertEquals(1, weekly.conversations)
        assertEquals(2, monthly.conversations)
        assertEquals(3, monthly.users)
        assertEquals(2, monthly.channels)
        assertEquals(33, monthly.tokensConsumed)
    }

    @Test
    fun `returns zero counts when no sessions started in the period`() {
        val stats = WeeklyStatsService(TestConversationStateStore()).gather(Instant.now())

        assertEquals(0, stats.users)
        assertEquals(0, stats.conversations)
        assertEquals(0, stats.tokensConsumed)
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
