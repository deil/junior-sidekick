package com.github.uncomplexco.sidekick.application.tools.integrations

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class InternalFileExchangeToolsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `publishes markdown file with fake internal URL`() {
        val result = tools().publishFileInternally("session:/tmp/file.md", "file.md", "text/markdown")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `accepts plain text files`() {
        val result = tools().publishFileInternally("session:/tmp/file.txt", "file.txt", "text/plain")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `rejects unsupported mime types`() {
        assertThrows<ToolException.ValidationFailure> {
            tools().publishFileInternally("session:/tmp/file.pdf", "file.pdf", "application/pdf")
        }
    }

    private fun tools(): InternalFileExchangeTools =
        InternalFileExchangeTools(
            object : FilePublisher {
                override fun publishFile(
                    path: String,
                    title: String,
                    mimeType: String,
                ): FilePublisher.Result = FilePublisher.Result.Ok("https://files.internal/$title")

                override fun publishContent(
                    content: String,
                    title: String,
                    mimeType: String,
                ): FilePublisher.Result = FilePublisher.Result.Ok("https://files.internal/$title")
            },
            TurnContext(
                conversationId = ConversationId("C123", "1700000000.000"),
                turnId = "turn",
                currentMessageIds = listOf("m1"),
                currentFiles = emptyList(),
                sessionFiles = emptyList(),
                compactions = emptyList(),
                history = emptyList(),
            ),
            dir,
        )
}
