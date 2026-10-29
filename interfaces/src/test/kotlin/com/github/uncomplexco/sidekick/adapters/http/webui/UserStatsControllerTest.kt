package com.github.uncomplexco.sidekick.adapters.http.webui

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class UserStatsControllerTest {
    @TempDir lateinit var dir: Path

    private fun store() =
        FilesystemConversationStateStore(
            AgentConfig(
                "Juniper",
                dir.resolve("state").toString(),
                dir.resolve("workspace").toString(),
            ),
            ChatPlatform.SLACK,
        )

    @Test
    fun `serves real usage aggregates for both supported periods`() {
        val store = store()
        for ((id, users) in
            listOf(
                ConversationId("C1", "thread1") to listOf("U1", "U2"),
                ConversationId("C1", "thread2") to listOf("U2"),
                ConversationId("C2", "thread3") to listOf("U3"),
            )) {
            val state = store.load(id)
            state.messages =
                users
                    .mapIndexed { index, user ->
                        SessionMessage(
                            id = "$index",
                            role = SessionMessageRole.USER,
                            author = MessageAuthor(user, null),
                            text = "Hello",
                            createdAtMs = 1,
                        )
                    }
                    .toMutableList()
            state.stats = state.stats.copy(consumedInputTokens = 42, consumedOutputTokens = 8)
            store.save(id, state)
        }
        val clock = Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC)
        val mvc =
            MockMvcBuilders.standaloneSetup(UserStatsController(WeeklyStatsService(store), clock))
                .build()

        for (days in listOf(7, 30)) {
            mvc.get("/api/web/user/stats") { param("days", "$days") }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.period_days") { value(days) }
                    jsonPath("$.users") { value(3) }
                    jsonPath("$.conversations") { value(3) }
                    jsonPath("$.channels") { value(2) }
                    jsonPath("$.tokens_consumed") { value(150) }
                }
        }
    }

    @Test
    fun `defaults to seven days and returns zero for an empty installation`() {
        val mvc =
            MockMvcBuilders.standaloneSetup(UserStatsController(WeeklyStatsService(store())))
                .build()

        mvc.get("/api/web/user/stats").andExpect {
            status { isOk() }
            jsonPath("$.period_days") { value(7) }
            jsonPath("$.users") { value(0) }
            jsonPath("$.conversations") { value(0) }
        }
    }

    @Test
    fun `rejects unsupported or malformed periods`() {
        val mvc =
            MockMvcBuilders.standaloneSetup(UserStatsController(WeeklyStatsService(store())))
                .build()

        for (days in listOf("0", "-1", "14", "365", "abc")) {
            mvc.get("/api/web/user/stats") { param("days", days) }
                .andExpect { status { isBadRequest() } }
        }
    }
}
