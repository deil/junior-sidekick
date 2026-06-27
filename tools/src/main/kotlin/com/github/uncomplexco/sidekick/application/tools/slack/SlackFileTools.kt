package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFiles
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.springframework.util.MimeType
import org.springframework.util.MimeTypeUtils

@LLMDescription("Slack file tools for the current inbound message")
class SlackFileTools(
    private val ctx: TurnContext,
    private val virtualPaths: VirtualPaths,
) : ToolSet {
    private val files = WorkspaceFiles(virtualPaths.sessionRoot)

    @Tool
    @LLMDescription(
        "Read a file attached to the current thread. If the file does not exist, a error is returned.",
    )
    fun slackFileRead(
        @LLMDescription("ID or permalink of the file") fileId: String,
        @LLMDescription("The line number to start reading from (1-indexed)") offset: Int? = null,
        @LLMDescription("The maximum number of lines to read (defaults to 2000)") limit: Int? = null,
    ): String {
        val file = ctx.sessionFiles.find { it.id == fileId || it.displayName == fileId }!!
        validate(isSupportedSlackTextFile(file)) { "Only text Slack files are supported." }

        val realPath = parseVirtualPath(file.localPath, virtualPaths)
        return files.read(realPath, offset, limit, file.localPath)
    }
}

fun isSupportedSlackTextFile(file: SessionFileRef): Boolean {
    val mime = MimeType.valueOf(file.mimetype!!.lowercase())
    return mime.type == "text" || SUPPORTED_SLACK_TEXT_MIMETYPES.contains(mime)
}

private val SUPPORTED_SLACK_TEXT_MIMETYPES =
    setOf(
        MimeTypeUtils.APPLICATION_JSON,
        MimeTypeUtils.APPLICATION_XML,
        MimeTypeUtils.TEXT_XML,
    )

private val SUPPORTED_SLACK_TEXT_FILETYPES = setOf("markdown", "md", "html", "text", "txt", "plain_text")
