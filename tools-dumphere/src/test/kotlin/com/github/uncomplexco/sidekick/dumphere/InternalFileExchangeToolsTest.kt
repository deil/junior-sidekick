package com.github.uncomplexco.sidekick.dumphere

import ai.koog.agents.core.tools.ToolException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InternalFileExchangeToolsTest {
    @Test
    fun `publishes markdown file with fake internal URL`() {
        val result =
            tools().publishFileInternally("/data/session/file.md", "file.md", "text/markdown")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `accepts plain text files`() {
        val result =
            tools().publishFileInternally("/data/session/file.txt", "file.txt", "text/plain")

        assertTrue(result.ok)
        assertTrue(result.url!!.startsWith("https://files.internal/"))
    }

    @Test
    fun `resolves virtual paths before publishing files`() {
        var publishedPath: String? = null
        val publisher = fakePublisher { path -> publishedPath = path }
        val tools =
            InternalFileExchangeTools(publisher) {
                Path.of("/resolved").resolve(it.removePrefix("/"))
            }

        val result =
            tools.publishFileInternally("/data/session/file.md", "file.md", "text/markdown")

        assertTrue(result.ok)
        assertEquals("/resolved/data/session/file.md", publishedPath)
    }

    @Test
    fun `rejects unsupported mime types`() {
        assertThrows<ToolException.ValidationFailure> {
            tools().publishFileInternally("/data/session/file.pdf", "file.pdf", "application/pdf")
        }
    }

    @Test
    fun `reads published file contents through publisher`() {
        val result = InternalFileReadTools(fakePublisher()).readInternalSnippet("page1", 2, 5)

        assertEquals("contents page1 2 5", result)
    }

    @Test
    fun `edits published file contents through publisher`() {
        val result = tools().editInternalSnippet("page1", "old", "new", true)

        assertEquals("edited page1 old new true", result)
    }

    @Test
    fun `resolves virtual path before replacing artifact contents`() {
        var uploadedPath: String? = null
        val tools =
            InternalFileExchangeTools(fakePublisher { uploadedPath = it }) {
                Path.of("/resolved").resolve(it.removePrefix("/"))
            }

        val result = tools.replaceInternalFile("page1", "/data/session/report.html")

        assertEquals("https://files.internal/page1", result.url)
        assertEquals("/resolved/data/session/report.html", uploadedPath)
    }

    private fun tools(): InternalFileExchangeTools =
        InternalFileExchangeTools(fakePublisher()) { Path.of(it) }

    private fun fakePublisher(onPublishFile: (String) -> Unit = {}): FilePublisher =
        object : FilePublisher {
            override fun publishFile(
                path: String,
                title: String,
                mimeType: String,
            ): FilePublisher.Result {
                onPublishFile(path)
                return FilePublisher.Result.Ok("https://files.internal/$title")
            }

            override fun publishContent(
                content: String,
                title: String,
                mimeType: String,
            ): FilePublisher.Result = FilePublisher.Result.Ok("https://files.internal/$title")

            override fun readFileContents(
                id: String,
                offset: Int?,
                limit: Int?,
            ): String = "contents $id $offset $limit"

            override fun replaceFile(id: String, path: String): InternalFileReplaceResult {
                onPublishFile(path)
                return InternalFileReplaceResult(
                    id,
                    "Report",
                    "https://files.internal/$id",
                    2,
                    1024,
                    "HTML",
                    "2026-11-06T12:00:00Z",
                    false,
                )
            }

            override fun editFileContents(
                id: String,
                oldString: String,
                newString: String,
                replaceAll: Boolean,
            ): String = "edited $id $oldString $newString $replaceAll"
        }
}
