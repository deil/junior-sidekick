package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal fun renderFileAttachments(
    sessionId: SessionId,
    files: List<SessionFileRef>,
    basePath: Path,
    maxChars: Int,
): String =
    xmlTag(
        "attachments",
        files.joinToString("\n") {
            val data = fileDataBase64(sessionId, it, basePath, maxChars)
            buildString {
                appendLine("<attachment id=\"${escapeXml(it.id)}\">")
                appendLine("filename: ${escapeXml(it.name)}")
                appendLine("public_share_url: ${escapeXml(it.displayName)}")
                appendLine("mime_type: ${escapeXml(it.mimetype!!)}")
                /*appendLine("encoding: base64")
                appendLine("truncated: ${data.truncated}")
                appendLine("<data_base64>${data.text}</data_base64>")*/
                appendLine("local_path: ${escapeXml(it.localPath)}")
                appendLine("</attachment>")
            }
        },
    )

private fun fileDataBase64(
    sessionId: SessionId,
    file: SessionFileRef,
    basePath: Path,
    maxChars: Int,
): FileDataBase64 {
    val sessionFolder = sessionId.folder(basePath).normalize()
    val filePath = sessionFolder.resolve(file.localPath).normalize()
    if (!filePath.startsWith(sessionFolder) || !Files.exists(filePath)) {
        return FileDataBase64("", truncated = false)
    }

    val encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath))
    return FileDataBase64(
        text = encoded.take(maxChars),
        truncated = encoded.length > maxChars,
    )
}

private data class FileDataBase64(
    val text: String,
    val truncated: Boolean,
)
