package com.github.uncomplexco.sidekick.application.tools.integrations

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalFileExchangeToolsTest {
    @TempDir
    lateinit var dir: Path

    private val conversationId = ConversationId("C123", "1700000000.000")

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
    fun `resolves session paths before publishing file`() {
        var publishedPath: String? = null
        val result =
            tools(
                object : FilePublisher {
                    override fun publishFile(
                        path: String,
                        title: String,
                        mimeType: String,
                    ): FilePublisher.Result {
                        publishedPath = path
                        return FilePublisher.Result.Ok("https://files.internal/$title")
                    }

                    override fun publishContent(
                        content: String,
                        title: String,
                        mimeType: String,
                    ): FilePublisher.Result = FilePublisher.Result.Ok("https://files.internal/$title")
                },
            ).publishFileInternally("session:/tmp/file.md", "file.md", "text/markdown")

        assertTrue(result.ok)
        assertEquals(conversationId.folder(dir).resolve("tmp/file.md").toString(), publishedPath)
    }

    @Test
    fun `rejects unsupported mime types`() {
        assertThrows<ToolException.ValidationFailure> {
            tools().publishFileInternally("session:/tmp/file.pdf", "file.pdf", "application/pdf")
        }
    }

    private fun tools(
        filePublisher: FilePublisher =
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
    ): InternalFileExchangeTools =
        InternalFileExchangeTools(
            filePublisher,
            TurnContext(
                conversationId = conversationId,
                turnId = "turn",
                currentMessageIds = listOf("m1"),
                currentFiles = emptyList(),
                sessionFiles = emptyList(),
                history =
                    ConversationHistory(
                        compactions = emptyList(),
                        messages = emptyList(),
                        hasKoogMessages = false,
                    ),
            ),
            dir,
            dir.resolve("workspace/skills"),
        )
}
