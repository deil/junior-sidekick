package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.checkpoints.TurnCheckpointPersistence
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SidekickAgentIdentityTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mcp connection uses sole author identity or null for multiple authors`(
        multipleAuthors: Boolean
    ) = runBlocking {
        // Arrange
        val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString())
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val sessions =
            SessionManager(
                store,
                VirtualPathsFactory(config, ChatPlatform.SLACK),
                SessionContextCompactor { _, _, _ -> "summary" },
            )
        val conversationId = ConversationId("C123", "thread")
        val turn =
            sessions.recordIncomingMessages(
                conversationId,
                false,
                { emptyList() },
                listOf(
                    message("m1", "alice"),
                    message("m2", if (multipleAuthors) "bob" else "alice"),
                ),
                emptyList(),
            )
        val registry = mock(McpServersRegistry::class.java)
        val identity = if (multipleAuthors) null else "alice"
        val unavailable = IllegalStateException("MCP unavailable")
        `when`(
                registry.connect(
                    conversationId,
                    identity,
                    turn.context.conversation.virtualPaths.workRoot,
                )
            )
            .thenThrow(unavailable)
        val agent =
            SidekickAgent(
                config,
                mock(KoogConfig::class.java),
                SystemPromptBuilder(config, ChatPlatform.SLACK),
                TurnPromptBuilder(config) { SkillCatalog(emptyList()) },
                mock(ToolRegistryFactory::class.java),
                registry,
                mock(ChatHistoryProvider::class.java),
                TurnCheckpointPersistence(store),
            )

        // Act
        val failure =
            assertFailsWith<IllegalStateException> {
                agent.execute(turn, mock(ChatPlatformAdapter::class.java)) { null }
            }

        // Assert
        assertSame(unavailable, failure)
        verify(registry)
            .connect(conversationId, identity, turn.context.conversation.virtualPaths.workRoot)
    }

    private fun message(id: String, author: String) =
        SessionMessage(
            id = id,
            role = SessionMessageRole.USER,
            author = MessageAuthor(author, author),
            text = "request $id",
            createdAtMs = 1,
        )
}
