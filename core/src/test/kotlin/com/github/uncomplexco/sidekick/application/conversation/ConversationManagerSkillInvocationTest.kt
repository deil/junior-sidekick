package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationManagerSkillInvocationTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `persists materialized explicit skill invocation on current message`() =
        runBlocking {
            // Arrange
            val conversationId = ConversationId("C123", "1700000000.000")
            val store = store()
            val manager = manager(store)

            // Act
            manager.recordIncomingMessages(
                conversationId = conversationId,
                seedHistory = false,
                historyLoader = { emptyList() },
                messages =
                    listOf(
                        message(
                            id = "current",
                            text = "please /code-review this",
                            explicitSkillInvocation = ExplicitSkillInvocation("code-review"),
                        ),
                    ),
                files = emptyList(),
            )

            // Assert
            val saved = store.load(conversationId).messages.single()
            assertEquals("code-review", saved.explicitSkillInvocation?.skillName)
        }

    @Test
    fun `does not materialize explicit skill invocation for seeded history`() =
        runBlocking {
            // Arrange
            val conversationId = ConversationId("C123", "1700000000.000")
            val store = store()
            val manager = manager(store)

            // Act
            manager.recordIncomingMessages(
                conversationId = conversationId,
                seedHistory = true,
                historyLoader =
                    {
                        listOf(
                            ChatMessage(
                                id = "seed",
                                role = SessionMessageRole.USER,
                                author = author(),
                                text = "please /code-review this historical message",
                                timestamp = 1,
                                files = emptyList(),
                            ),
                        )
                    },
                messages = listOf(message(id = "current", text = "please review this", createdAtMs = 2)),
                files = emptyList(),
            )

            // Assert
            val seeded = store.load(conversationId).messages.single { it.id == "seed" }
            assertNull(seeded.explicitSkillInvocation)
        }

    private fun manager(store: FilesystemConversationStateStore): ConversationManager {
        val config = config()

        return ConversationManager(
            store,
            SessionContextCompactor(
                TurnPromptBuilder(config, skills = { SkillCatalog(emptyList()) }),
                summarizer = { _, messages, _ -> "summary for ${messages.size} messages" },
            ),
        )
    }

    private fun store(): FilesystemConversationStateStore = FilesystemConversationStateStore(config())

    private fun config(): AgentConfig = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())

    private fun message(
        id: String,
        text: String,
        createdAtMs: Long = 1,
        explicitSkillInvocation: ExplicitSkillInvocation? = null,
    ): SessionMessage =
        SessionMessage(
            id = id,
            role = SessionMessageRole.USER,
            author = author(),
            text = text,
            createdAtMs = createdAtMs,
            explicitSkillInvocation = explicitSkillInvocation,
        )

    private fun author(): MessageAuthor = MessageAuthor(username = "U123", fullName = "User")
}
