package com.github.uncomplexco.sidekick.adapters.koog

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.koog.QueuedPromptExecutor
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.context.prompts.Prompts
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic

class KoogSessionContextSummarizerTest {
    @Test
    fun `compaction sends unchanged user-only transcript through shared runtime`() = runBlocking {
        val executor = QueuedPromptExecutor(Message.Assistant("Summary", ResponseMetaInfo.Empty))
        val previous = SessionCompaction("previous", 0, "Earlier work", listOf("old"))

        val result = summarize(executor, listOf(previous))

        assertEquals("Summary", result)
        val message = assertIs<Message.User>(executor.prompts.single().messages.single())
        assertContains(message.textContent(), Prompts.CONTEXT_COMPACTION_PROMPT)
        assertContains(message.textContent(), "Earlier work")
        assertContains(message.textContent(), "[USER] Current request\n")
    }

    @Test
    fun `generated summary has surrounding whitespace removed`() = runBlocking {
        val executor =
            QueuedPromptExecutor(Message.Assistant("  Summary\n", ResponseMetaInfo.Empty))

        val result = summarize(executor)

        assertEquals("Summary", result)
    }

    @Test
    fun `generated summary remains capped`() = runBlocking {
        val executor =
            QueuedPromptExecutor(Message.Assistant("x".repeat(3501), ResponseMetaInfo.Empty))

        val result = summarize(executor)

        assertEquals("x".repeat(3500), result)
    }

    @Test
    fun `blank model response falls back to transcript`() = runBlocking {
        val executor = QueuedPromptExecutor(Message.Assistant(" \n", ResponseMetaInfo.Empty))

        val result = summarize(executor)

        assertEquals("[USER] Current request\n\n", result)
    }

    @Test
    fun `model failure falls back to transcript`() = runBlocking {
        val executor = QueuedPromptExecutor(beforeResponse = { error("Model unavailable") })

        val result = summarize(executor)

        assertEquals("[USER] Current request\n\n", result)
    }

    private suspend fun summarize(
        executor: QueuedPromptExecutor,
        compactions: List<SessionCompaction> = emptyList(),
    ): String {
        val config =
            KoogConfig(
                "unused",
                "Test",
                "",
                "test",
                "test",
                "low",
                "test",
                "test",
                "medium",
                "test",
                "test",
                "high",
                "image",
                10,
            )
        return mockStatic(
                Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
            )
            .use { model ->
                model.`when`<PromptExecutor> { config.openRouterExecutor() }.thenReturn(executor)
                KoogSessionContextSummarizer(config)
                    .summarize(
                        ConversationId("C123", "thread"),
                        compactions,
                        listOf(
                            SessionMessage(
                                "current",
                                SessionMessageRole.USER,
                                text = "Current request",
                                createdAtMs = 1,
                            )
                        ),
                    )
            }
    }
}
