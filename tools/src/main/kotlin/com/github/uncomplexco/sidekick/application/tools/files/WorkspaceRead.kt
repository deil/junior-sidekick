package com.github.uncomplexco.sidekick.application.tools.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.DATA_ROOT
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPageOptions
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import kotlinx.coroutines.runBlocking

class WorkspaceRead(
    private val projectId: String,
    private val filesystem: WorkspaceFilesystem,
) {
    fun read(
        path: String,
        offset: Int?,
        limit: Int?,
        displayPath: String? = null,
    ): String {
        require(offset == null || offset >= 1) { "`offset` must be greater than or equal to 1" }

        val normalizedPath = if (path == VIRTUAL_ROOT) path else path.trimEnd('/')
        if (normalizedPath == VIRTUAL_ROOT || normalizedPath == DATA_ROOT) {
            return ls(normalizedPath, displayPath ?: normalizedPath, offset, limit)
        }

        val metadata = runBlocking { filesystem.stat(projectId, normalizedPath) }
        require(metadata != null) { "File not found: $normalizedPath" }

        return if (metadata.kind == FileKind.DIRECTORY) {
            ls(normalizedPath, displayPath ?: normalizedPath, offset, limit)
        } else {
            readFile(normalizedPath, displayPath ?: normalizedPath, offset, limit)
        }
    }

    private fun ls(
        path: String,
        displayTarget: String,
        offset: Int?,
        limit: Int?,
    ): String {
        val currentOffset = offset ?: 1

        val page = runBlocking {
            filesystem.listDirectory(
                projectId,
                path,
                DirectoryPageOptions(currentOffset, limit ?: DEFAULT_READ_LIMIT),
            )
        }

        val entries = page.entries.map { it.name + if (it.kind == FileKind.DIRECTORY) "/" else "" }

        return listOf(
                "<path>$displayTarget</path>",
                "<type>directory</type>",
                "<entries>",
                entries.joinToString("\n"),
                if (page.truncated) {
                    "\n(Showing ${entries.size} of ${page.total} entries. Use 'offset' parameter to read beyond entry ${currentOffset + entries.size})"
                } else {
                    "\n(${page.total} entries)"
                },
                "</entries>",
            )
            .joinToString("\n")
    }

    private fun readFile(
        path: String,
        displayTarget: String,
        offset: Int?,
        limit: Int?,
    ): String {
        val startOffset = offset ?: 1

        val file = runBlocking {
            filesystem.readText(
                projectId,
                path,
                TextReadOptions(startOffset, limit ?: DEFAULT_READ_LIMIT, MAX_BYTES),
            )
        }

        val lines =
            file.content.reader().buffered().use { reader ->
                reader.readLines().map { line ->
                    if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) + MAX_LINE_SUFFIX
                    else line
                }
            }

        return buildString {
            appendLine(
                """
                <path>$displayTarget</path>
                <type>file</type>
                <content>
                """
                    .trimIndent()
            )

            lines.forEachIndexed { index, line -> appendLine("${index + startOffset}: $line") }

            appendLine()
            appendLine()

            val last = startOffset + lines.size - 1
            val next = last + 1

            if (file.lastLineTruncated) {
                appendLine(
                    "(Output capped at $MAX_BYTES_LABEL partway through line $last. " +
                        "The remainder of that line is omitted.)"
                )
            } else if (file.totalLines == null) {
                appendLine(
                    "(Output capped at $MAX_BYTES_LABEL. Showing lines $startOffset-$last. " +
                        "Use offset=$next to continue.)"
                )
            } else if (file.truncated) {
                appendLine(
                    "(Showing lines $startOffset-$last of ${file.totalLines}. Use offset=$next to continue.)"
                )
            } else {
                appendLine("(End of file - total ${file.totalLines} lines)")
            }

            append("</content>")
        }
    }

    companion object {
        const val DEFAULT_READ_LIMIT = 2000
        const val MAX_LINE_SUFFIX = "... (line truncated to 2000 chars)"
        const val MAX_BYTES = 50 * 1024
        const val MAX_BYTES_LABEL = "50 KB"
        private const val MAX_LINE_LENGTH = 2000
        private const val VIRTUAL_ROOT = "/"
    }
}
