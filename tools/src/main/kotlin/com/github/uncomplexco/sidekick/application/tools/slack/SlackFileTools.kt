package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFiles
import com.github.uncomplexco.sidekick.application.tools.files.parseVirtualPath
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import kotlinx.serialization.Serializable
import java.net.http.HttpClient
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
    private val files = WorkspaceFiles(dataDirectory)

    @Tool
    @LLMDescription(
        "Read a file attached to the current thread. If the file does not exist, a error is returned.",
    )
    fun slackFileRead(
        @LLMDescription("ID or permalink of the file") fileId: String,
        @LLMDescription("The line number to start reading from (1-indexed)") offset: Int? = null,
        @LLMDescription("The maximum number of lines to read (defaults to 2000)") limit: Int? = null,
    ): String {
        /*
        validate(ctx.currentFiles.isNotEmpty()) { "No Slack file is attached to the current message." }
        validate(ctx.currentFiles.size == 1) { "Sorry, only one file at a time." }
        val file = ctx.sessionFiles.find { it.id == fileId || it.displayName == fileId }!!
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
         */

        val file = ctx.sessionFiles.find { it.id == fileId || it.displayName == fileId }!!
        validate(isSupportedSlackTextFile(file)) { "Only markdown, HTML, and plain text Slack files are supported." }

        val sessionRoot = ctx.sessionId.folder(dataDirectory)
        val realPath = parseVirtualPath(file.localPath, sessionRoot)
        return files.read(realPath, offset, limit, sessionRoot.toString())
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

fun isSupportedSlackTextFile(file: SessionFileRef): Boolean {
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

fun downloadFileName(file: SessionFileRef): String {
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
