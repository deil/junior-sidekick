package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFiles
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.util.MimeType
import org.springframework.util.MimeTypeUtils
import java.net.http.HttpClient
import java.time.Duration

@LLMDescription("Slack file tools for the current inbound message")
class SlackFileTools(
    private val ctx: TurnContext,
    private val slackBotToken: String,
    private val virtualPaths: VirtualPaths,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) : ToolSet {
    private val files = WorkspaceFiles(virtualPaths.sessionRoot)

    companion object {
        private val log = LoggerFactory.getLogger(SlackFileTools::class.java)
    }

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
        validate(isSupportedSlackTextFile(file)) { "Only text Slack files are supported." }

        val realPath = parseVirtualPath(file.localPath, virtualPaths)
        return files.read(realPath, offset, limit, file.localPath)
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
    val mime = MimeType.valueOf(file.mimetype!!.lowercase())
    return mime.type == "text" || SUPPORTED_SLACK_TEXT_MIMETYPES.contains(mime)
}

fun downloadFileName(file: SessionFileRef): String {
    return "${sanitizeFileName(file.id)}-${sanitizeFileName(file.name)}"
}

private fun sanitizeFileName(value: String): String =
    value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .ifBlank { "file" }

private val SUPPORTED_SLACK_TEXT_MIMETYPES =
    setOf(
        MimeTypeUtils.APPLICATION_JSON,
        MimeTypeUtils.APPLICATION_XML,
        MimeTypeUtils.TEXT_XML,
    )

private val SUPPORTED_SLACK_TEXT_FILETYPES = setOf("markdown", "md", "html", "text", "txt", "plain_text")
