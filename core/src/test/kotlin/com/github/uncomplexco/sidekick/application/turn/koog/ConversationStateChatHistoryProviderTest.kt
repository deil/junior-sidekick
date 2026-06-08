package com.github.uncomplexco.sidekick.application.turn.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class ConversationStateChatHistoryProviderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `generates ids for new koog messages when storing`() =
        runBlocking {
            // Arrange
            val store = store()
            val provider = ConversationStateChatHistoryProvider(store)
            val requestMetaInfo = RequestMetaInfo(Instant.parse("2026-01-01T00:00:00Z"))
            val responseMetaInfo = ResponseMetaInfo(Instant.parse("2026-01-01T00:00:01Z"))
            val messages =
                listOf(
                    Message.User("existing", requestMetaInfo, id = "existing-id"),
                    Message.Assistant("new", responseMetaInfo),
                )

            // Act
            provider.store("C123:1700000000.000", messages)
            val saved = store.load(ConversationId("C123", "1700000000.000")).koogMessages

            // Assert
            assertEquals("existing-id", saved[0].id)
            assertNotNull(saved[1].id)
        }

    private fun store(): FilesystemConversationStateStore =
        FilesystemConversationStateStore(
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString()),
        )
}
