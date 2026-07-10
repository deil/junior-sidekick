package com.github.uncomplexco.sidekick.application.tools.integrations

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPath
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType

interface FilePublisher {
    fun publishFile(
        path: String,
        title: String,
        mimeType: String,
    ): Result

    fun publishContent(
        content: String,
        title: String,
        mimeType: String,
    ): Result

    fun readFileContents(
        id: String,
        offset: Int?,
        limit: Int?,
    ): String

    fun editFileContents(
        id: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): String

    sealed interface Result {
        data class Ok(
            val url: String,
        ) : Result

        data class Error(
            val message: String,
        ) : Result
    }
}

@LLMDescription("Internal file exchange tools")
class InternalFileExchangeTools(
    private val filePublisher: FilePublisher,
    private val virtualPaths: VirtualPaths,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Publish a file to the internal file exchange and returns a secure share URL. Use this for files that exist in the workspace, or attached files that are already downloaded",
    )
    fun publishFileInternally(
        @LLMDescription("Absolute path to the file to publish")
        path: VirtualPath,
        @LLMDescription("Name of the file")
        title: String,
        @LLMDescription("File MIME type. Only text files are accepted")
        mimeType: String,
    ): InternalFilePublishResult {
        val mediaType = MediaType.parseMediaType(mimeType)
        validate(mediaType in SUPPORTED_INTERNAL_FILE_MIME_TYPES) {
            "Only text, HTML or Markdown files can be published."
        }

        try {
            val realPath = parseVirtualPath(path, virtualPaths)
            return when (val result = filePublisher.publishFile(realPath, title, mimeType)) {
                is FilePublisher.Result.Error -> {
                    throw ToolException.ValidationFailure(result.message)
                }

                is FilePublisher.Result.Ok -> {
                    InternalFilePublishResult(
                        ok = true,
                        url = result.url,
                    )
                }
            }
        } catch (ex: Throwable) {
            return InternalFilePublishResult(
                ok = false,
                error = ex.message ?: "Unknown error",
            )
        }
    }

    @Tool
    @LLMDescription(
        "Publish new inline Markdown/HTML/text content to the internal file exchange and return a secure share URL. Do not use this for files that exist in the workspace unless you intentionally transformed or extracted content",
    )
    fun publishSnippetInternally(
        @LLMDescription("HTML or Markdown content to publish.")
        content: String,
        @LLMDescription("Snippet title")
        title: String,
        @LLMDescription("MIME type of the content. Only text, HTML or Markdown are accepted.")
        mimeType: String,
    ): InternalFilePublishResult {
        val mediaType = MediaType.parseMediaType(mimeType)
        validate(mediaType in SUPPORTED_INTERNAL_FILE_MIME_TYPES) {
            "Only plain text, HTML or Markdown can be published."
        }

        try {
            return when (val result = filePublisher.publishContent(content, title, mimeType)) {
                is FilePublisher.Result.Error -> {
                    throw ToolException.ValidationFailure(result.message)
                }

                is FilePublisher.Result.Ok -> {
                    InternalFilePublishResult(
                        ok = true,
                        url = result.url,
                    )
                }
            }
        } catch (ex: Throwable) {
            return InternalFilePublishResult(
                ok = false,
                error = ex.message ?: "Unknown error",
            )
        }
    }

    @Tool
    @LLMDescription(
        """
        Read a published HTML or Markdown file. If the page does not exist, an error is returned.

        Usage:
        - By default, this tool returns up to 2000 lines from the start of the file.
        - The offset parameter is the line number to start reading from (1-indexed).
        - To read later sections, call this tool again with a larger offset.
        - Contents are returned with each line prefixed by its line number as `<line>: <content>`.
        - Any line longer than 2000 characters is truncated.
        """,
    )
    fun readInternalSnippet(
        @LLMDescription("Published page id")
        id: String,
        @LLMDescription("The line number to start reading from (1-indexed)")
        offset: Int?,
        @LLMDescription("The maximum number of lines to read (defaults to 2000)")
        limit: Int?,
    ): String = filePublisher.readFileContents(id, offset, limit)

    @Tool
    @LLMDescription(
        """
        Performs exact string replacements in a published HTML or Markdown file.

        Usage:
        - Read the page before editing so you can copy exact content and line context.
        - When editing text from read_file_contents output, never include the line number prefix in oldString or newString.
        - The edit fails if oldString is not found in the current file.
        - The edit fails if oldString matches multiple times and replaceAll is not true.
        - Use replaceAll for renaming or replacing every occurrence.
        """,
    )
    fun editInternalSnippet(
        @LLMDescription("Published page id")
        id: String,
        @LLMDescription("The text to replace")
        oldString: String,
        @LLMDescription("The text to replace it with (must be different from oldString)")
        newString: String,
        @LLMDescription("Replace all occurrences of oldString (default false)")
        replaceAll: Boolean?,
    ): String = filePublisher.editFileContents(id, oldString, newString, replaceAll ?: false)
}

@Serializable
data class InternalFilePublishResult(
    val ok: Boolean,
    val url: String? = null,
    val error: String? = null,
)

private val SUPPORTED_INTERNAL_FILE_MIME_TYPES =
    setOf(MediaType.TEXT_MARKDOWN, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN)
