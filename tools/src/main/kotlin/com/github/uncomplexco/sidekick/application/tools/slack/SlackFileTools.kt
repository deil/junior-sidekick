package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.IncomingChatFile
import com.github.uncomplexco.sidekick.application.sessions.TurnContext
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@LLMDescription("Slack file tools for the current inbound message")
class SlackFileTools(
    private val ctx: TurnContext,
    private val slackBotToken: String,
    private val dataDirectory: Path,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) : ToolSet {
    @Tool
    @LLMDescription(
        "Read the single markdown, HTML, or plain text file attached to the current inbound Slack message and return its text. Only uses files from the current runtime context; does not resolve arbitrary Slack links from message text.",
    )
    fun slackFileDownload(): SlackFileDownloadResult {
        validate(ctx.currentFiles.isNotEmpty()) { "No Slack file is attached to the current message." }
        validate(ctx.currentFiles.size == 1) { "Sorry, only one file at a time." }

        val file = ctx.currentFiles.single()
        validate(isSupportedSlackTextFile(file)) { "Only markdown, HTML, and plain text Slack files are supported." }
        val downloadUrl = requireNotNull(file.urlPrivateDownload) { "Slack file does not include a private download URL." }

        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer $slackBotToken")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        check(response.statusCode() in 200..299) { "Slack file download failed with HTTP ${response.statusCode()}." }

        val body = response.body()
        Files.createDirectories(dataDirectory)
        val target = dataDirectory.resolve(downloadFileName(file))
        Files.write(target, body)

        return SlackFileDownloadResult(
            ok = true,
            fileId = file.id,
            name = file.name,
            mimetype = file.mimetype,
            filetype = file.filetype,
            size = body.size.toLong(),
            path = target.toString(),
        )
    }
}

@Serializable
data class SlackFileDownloadResult(
    val ok: Boolean,
    val fileId: String,
    val name: String?,
    val mimetype: String?,
    val filetype: String?,
    val size: Long,
    val path: String,
)

fun isSupportedSlackTextFile(file: IncomingChatFile): Boolean {
    val mimetype = file.mimetype?.lowercase()
    val filetype = file.filetype?.lowercase()
    val name = file.name?.lowercase().orEmpty()
    return mimetype in SUPPORTED_SLACK_TEXT_MIMETYPES ||
        filetype in SUPPORTED_SLACK_TEXT_FILETYPES ||
        name.endsWith(".md") ||
        name.endsWith(".markdown") ||
        name.endsWith(".html") ||
        name.endsWith(".htm") ||
        name.endsWith(".txt")
}

fun downloadFileName(file: IncomingChatFile): String {
    val rawName = file.name ?: file.id
    return "${sanitizeFileName(file.id)}-${sanitizeFileName(rawName)}"
}

private fun sanitizeFileName(value: String): String =
    value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .ifBlank { "file" }

private val SUPPORTED_SLACK_TEXT_MIMETYPES = setOf("text/markdown", "text/html", "text/plain")

private val SUPPORTED_SLACK_TEXT_FILETYPES = setOf("markdown", "md", "html", "text", "txt", "plain_text")
