package com.github.uncomplexco.sidekick.application.tools.stats

import com.github.uncomplexco.sidekick.application.conversation.ActiveTurn
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import com.github.uncomplexco.sidekick.application.stats.ConversationUsage
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class StatsToolsTest {
    @Test
    fun `exposes installation weekly stats from the previous seven days`() {
        val now = Instant.parse("2026-08-14T09:00:00Z")
        val store = RecordingConversationStateStore()
        val tools = StatsTools(WeeklyStatsService(store), Clock.fixed(now, ZoneOffset.UTC))

        val names = tools.asTools().map { it.name }
        val result = tools.weeklyStats()

        assertEquals(listOf("stats__weekly"), names)
        assertEquals(now.minusSeconds(7 * 24 * 60 * 60).toEpochMilli(), store.startInclusiveMs)
        assertEquals(now.toEpochMilli(), store.endExclusiveMs)
        assertEquals(WeeklyStatsResult(2, 2, 350, 3), result)
    }
}

private class RecordingConversationStateStore : ConversationStateStore {
    var startInclusiveMs: Long? = null
    var endExclusiveMs: Long? = null

    override fun loadUsageStartedBetween(
        startInclusiveMs: Long,
        endExclusiveMs: Long,
    ): List<ConversationUsage> {
        this.startInclusiveMs = startInclusiveMs
        this.endExclusiveMs = endExclusiveMs
        return listOf(
            ConversationUsage("C1", setOf("U1", "U2"), 100, 20),
            ConversationUsage("C2", setOf("U2", "U3"), 200, 30),
        )
    }

    override fun exists(id: ConversationId): Boolean = error("Not used")

    override fun load(id: ConversationId): ConversationState = error("Not used")

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
