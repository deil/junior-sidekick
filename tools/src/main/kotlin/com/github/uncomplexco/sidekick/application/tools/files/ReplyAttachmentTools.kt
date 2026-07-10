package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPath
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import kotlinx.serialization.Serializable

class ReplyAttachmentTools(
    private val collector: ReplyAttachmentCollector,
) : ToolSet {
    @Tool("attachFile")
    @LLMDescription(
        "Attach a file to the Slack reply. Use this for files that exist in the workspace. Logs, screenshots, or other files, including created during the session.",
    )
    fun attachFile(
        @LLMDescription("Absolute path to the file to attach")
        path: VirtualPath,
        @LLMDescription("Optional file name override to display in Slack")
        name: String? = null,
        @LLMDescription("Optional MIME type override, e.g. text/markdown")
        mimeType: String? = null,
    ): AttachFileResult {
        validate(path.isNotBlank()) { "'path' must not be blank." }

        val attachment = collector.attach(path, name, mimeType)
        return AttachFileResult(attachment.name, attachment.mimeType, attachment.bytes)
    }
}

@Serializable
data class AttachFileResult(
    val filename: String,
    val mime_type: String,
    val bytes: Long,
)
