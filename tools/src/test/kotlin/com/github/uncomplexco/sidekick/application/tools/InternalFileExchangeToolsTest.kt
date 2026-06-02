package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

class InternalFileExchangeToolsTest {
    @Test
    fun `publishes markdown file with fake internal URL`() {
        val result = tools().publishFileInternally("/workspace/tmp/file.md", "file.md", "text/markdown")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `accepts plain text files`() {
        val result = tools().publishFileInternally("/workspace/tmp/file.txt", "file.txt", "text/plain")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `rejects unsupported mime types`() {
        assertThrows<ToolException.ValidationFailure> {
            tools().publishFileInternally("/workspace/tmp/file.pdf", "file.pdf", "application/pdf")
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
            },
        )
}
