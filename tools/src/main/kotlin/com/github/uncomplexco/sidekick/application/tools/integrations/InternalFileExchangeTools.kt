package com.github.uncomplexco.sidekick.application.tools.integrations

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType

interface FilePublisher {
    fun publishFile(
        path: String,
        title: String,
        mimeType: String,
    ): Result

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
) : ToolSet {
    @Tool
    @LLMDescription("Publish a markdown or HTML file to the internal file exchange and return a share URL.")
    fun publishFileInternally(
        @LLMDescription("Path to the file to publish.")
        path: String,
        @LLMDescription("Name of the file to publish.")
        title: String,
        @LLMDescription("File MIME type. Only text, HTML or Markdown files are accepted.")
        mimeType: String,
    ): InternalFilePublishResult {
        val mediaType = MediaType.parseMediaType(mimeType)
        validate(mediaType in SUPPORTED_INTERNAL_FILE_MIME_TYPES) {
            "Only text, HTML or Markdown files can be published."
        }

        try {
            return when (val result = filePublisher.publishFile(path, title, mimeType)) {
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
}

@Serializable
data class InternalFilePublishResult(
    val ok: Boolean,
    val url: String? = null,
    val error: String? = null,
)

private val SUPPORTED_INTERNAL_FILE_MIME_TYPES =
    setOf(MediaType.TEXT_MARKDOWN, MediaType.TEXT_HTML, MediaType.TEXT_PLAIN)
