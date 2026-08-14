package com.github.uncomplexco.sidekick.application.stats

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStats
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
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
        saveConversation(store, "C1", "T1", cutoff, 100, 20, "U1", "U2")
        saveConversation(store, "C1", "T2", executedAt.minusSeconds(1), 200, 30, "U2", "U3")
        saveConversation(store, "D1", "T3", executedAt.minus(Duration.ofDays(8)), 1_000, 500, "U4")
        saveConversation(store, "G1", "T4", executedAt, 1_000, 500, "U5")

        // Act
        val stats = WeeklyStatsService(store).gather(executedAt)

        // Assert
        assertEquals(1, stats.projects)
        assertEquals(2, stats.conversations)
        assertEquals(350, stats.tokensConsumed)
        assertEquals(3, stats.users)
    }

    @Test
    fun `counts every user seen in a selected conversation`() {
        // Arrange
        val executedAt = Instant.parse("2026-08-14T09:00:00Z")
        val store = TestConversationStateStore()
        val state = store.add(ConversationId("C1", "T1"), executedAt.minus(Duration.ofDays(2)))
        state.messages += message("root", executedAt.minus(Duration.ofDays(2)), "U1")
        state.messages += message("future-reply", executedAt.plusSeconds(1), "U2")

        // Act
        val stats = WeeklyStatsService(store).gather(executedAt)

        // Assert
        assertEquals(2, stats.users)
    }

    private fun saveConversation(
        store: TestConversationStateStore,
        channelId: String,
        threadId: String,
        startedAt: Instant,
        inputTokens: Long,
        outputTokens: Long,
        vararg users: String,
    ) {
        val id = ConversationId(channelId, threadId)
        val state = store.add(id, startedAt)
        state.messages += users.mapIndexed { index, user -> message("$threadId-$index", startedAt.plusSeconds(index.toLong()), user) }
        state.stats = ConversationStats(consumedInputTokens = inputTokens, consumedOutputTokens = outputTokens)
    }

    private fun message(
        id: String,
        createdAt: Instant,
        user: String,
    ) = SessionMessage(
        id = id,
        role = SessionMessageRole.USER,
        author = MessageAuthor(username = user, fullName = null),
        text = "hello",
        createdAtMs = createdAt.toEpochMilli(),
    )

    private class TestConversationStateStore : ConversationStateStore {
        private val states = mutableMapOf<ConversationId, ConversationState>()
        private val startedAt = mutableMapOf<ConversationId, Long>()

        fun add(
            id: ConversationId,
            startedAt: Instant,
        ): ConversationState =
            ConversationState(id = id, files = mutableListOf()).also {
                states[id] = it
                this.startedAt[id] = startedAt.toEpochMilli()
            }

        override fun exists(id: ConversationId): Boolean = states.containsKey(id)

        override fun load(id: ConversationId): ConversationState = states.getValue(id)

        override fun loadStartedBetween(
            startInclusiveMs: Long,
            endExclusiveMs: Long,
        ): List<ConversationState> =
            startedAt
                .filterValues { it in startInclusiveMs..<endExclusiveMs }
                .keys
                .map(states::getValue)

        override fun save(
            id: ConversationId,
            state: ConversationState,
        ) {
            states[id] = state
        }

        override suspend fun <T> withSessionLock(
            id: ConversationId,
            block: suspend () -> T,
        ): T = block()
    }
}
