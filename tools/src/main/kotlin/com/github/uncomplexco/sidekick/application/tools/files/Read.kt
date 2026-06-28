package com.github.uncomplexco.sidekick.application.tools.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.DATA_ROOT
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.io.useLines
import kotlin.use

class WorkspaceRead(
    private val virtualPaths: VirtualPaths,
) {
    fun read(
        path: String,
        offset: Int?,
        limit: Int?,
        displayPath: String? = null,
    ): String {
        require(offset == null || offset >= 1) { "`offset` must be greater than or equal to 1" }

        val normalizedPath = if (path == VIRTUAL_ROOT) path else path.trimEnd('/')
        when (normalizedPath) {
            VIRTUAL_ROOT ->
                return renderLs(
                    normalizedPath,
                    virtualPaths.roots
                        .map { it.virtual.trimStart('/').substringBefore('/') + "/" }
                        .distinct(),
                    offset,
                    limit,
                )

            DATA_ROOT ->
                return renderLs(
                    normalizedPath,
                    virtualPaths.roots
                        .filter { it.virtual.startsWith("$DATA_ROOT/") }
                        .map { it.virtual.removePrefix("$DATA_ROOT/").substringBefore('/') + "/" }
                        .distinct(),
                    offset,
                    limit,
                )
        }

        val realPath =
            try {
                Path.of(parseVirtualPath(normalizedPath, virtualPaths)).toAbsolutePath().normalize()
            } catch (_: IllegalStateException) {
                throw IllegalArgumentException("Path not found: $normalizedPath")
            }
        val root =
            virtualPaths.roots
                .firstOrNull { normalizedPath == it.virtual || normalizedPath.startsWith("${it.virtual}/") }
                ?.real
                ?.toAbsolutePath()
                ?.normalize()
                ?: throw IllegalArgumentException("Path not found: $normalizedPath")
        val target = root.resolve(root.relativize(realPath)).normalize()
        require(target.startsWith(root)) { "Path escapes working directory: $normalizedPath" }

        var current = root
        for (segment in root.relativize(target)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw IllegalArgumentException("Symbolic links are not allowed in workspace paths: $current")
            }
        }

        if (Files.exists(target)) {
            val realTarget = target.toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(realTarget.startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) { "Path escapes working directory: $target" }
        }
        require(Files.exists(target)) { "File not found: ${target.pathString}" }

        return if (Files.isDirectory(target)) {
            ls(target, displayPath ?: normalizedPath, offset, limit)
        } else {
            readFile(target, displayPath ?: normalizedPath, offset, limit)
        }
    }

    fun ls(
        target: Path,
        displayTarget: String,
        offset: Int?,
        limit: Int?,
    ): String {
        val entries = Files.list(target).use { stream -> stream.toList().map { if (Files.isDirectory(it)) it.fileName.toString() + "/" else it.fileName.toString() } }
        return renderLs(displayTarget, entries, offset, limit)
    }

    private fun renderLs(
        displayTarget: String,
        entries: List<String>,
        offset: Int?,
        limit: Int?,
    ): String {
        val items = entries.sorted()
        val currentLimit = limit ?: DEFAULT_READ_LIMIT
        val currentOffset = offset ?: 1
        val start = currentOffset - 1
        val sliced = items.slice(start until minOf(start + currentLimit, items.size))
        val truncated = start + sliced.size < items.size

        return listOf(
            "<path>$displayTarget</path>",
            "<type>directory</type>",
            "<entries>",
            sliced.joinToString("\n"),
            if (truncated) {
                "\n(Showing ${sliced.size} of ${items.size} entries. Use 'offset' parameter to read beyond entry ${currentOffset + sliced.size})"
            } else {
                "\n(${items.size} entries)"
            },
            "</entries>",
        ).joinToString("\n")
    }

    fun readFile(
        target: Path,
        displayTarget: String,
        offset: Int?,
        limit: Int?,
    ): String {
        val ext =
            target.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        if (ext in BINARY_FILE_EXTENSIONS) {
            throw IllegalArgumentException("Cannot read binary file: ${target.pathString}")
        }

        val size = Files.size(target)
        if (size > 0L) {
            val sample = Files.newInputStream(target).use { it.readNBytes(minOf(4096, size.toInt())) }
            var nonPrintable = 0
            for (byte in sample) {
                val value = byte.toInt() and 0xFF
                if (value == 0) {
                    throw IllegalArgumentException("Cannot read binary file: ${target.pathString}")
                }
                if (value < 9 || (value > 13 && value < 32)) {
                    nonPrintable++
                }
            }
            if (nonPrintable.toDouble() / sample.size > 0.3) {
                throw IllegalArgumentException("Cannot read binary file: ${target.pathString}")
            }
        }

        val startOffset = offset ?: 1
        val currentLimit = limit ?: DEFAULT_READ_LIMIT
        val start = startOffset - 1
        val raw = mutableListOf<String>()
        var bytes = 0
        var count = 0
        var cut = false
        var more = false

        Files.newBufferedReader(target, StandardCharsets.UTF_8).useLines { lines ->
            for (text in lines) {
                count += 1
                if (count <= start) {
                    continue
                }

                if (raw.size >= currentLimit) {
                    more = true
                    continue
                }

                val line = if (text.length > MAX_LINE_LENGTH) text.substring(0, MAX_LINE_LENGTH) + MAX_LINE_SUFFIX else text
                val lineSize = line.toByteArray(StandardCharsets.UTF_8).size + if (raw.isNotEmpty()) 1 else 0
                if (bytes + lineSize > MAX_BYTES) {
                    cut = true
                    more = true
                    break
                }

                raw += line
                bytes += lineSize
            }
        }

        val file = ReadResult(raw, count, cut, more, startOffset)
        if (file.count < file.offset && !(file.count == 0 && file.offset == 1)) {
            throw IllegalArgumentException("Offset ${file.offset} is out of range for this file (${file.count} lines)")
        }

        return buildString {
            appendLine(
                """
                <path>$displayTarget</path>
                <type>file</type>
                <content>
                """.trimIndent(),
            )

            file.raw.mapIndexed { index, line -> appendLine("${index + file.offset}: $line") }

            appendLine()
            appendLine()

            val last = file.offset + file.raw.size - 1
            val next = last + 1

            if (file.cut) {
                appendLine("(Output capped at $MAX_BYTES_LABEL. Showing lines ${file.offset}-$last. Use offset=$next to continue.)")
            } else if (file.more) {
                appendLine("(Showing lines ${file.offset}-$last of ${file.count}. Use offset=$next to continue.)")
            } else {
                appendLine("(End of file - total ${file.count} lines)")
            }

            append("</content>")
        }
    }

    companion object {
        const val DEFAULT_READ_LIMIT = 2000
        const val MAX_LINE_SUFFIX = "... (line truncated to 2000 chars)"
        const val MAX_BYTES = 50 * 1024
        const val MAX_BYTES_LABEL = "50 KB"
        private const val VIRTUAL_ROOT = "/"
    }
}

private data class ReadResult(
    val raw: List<String>,
    val count: Int,
    val cut: Boolean,
    val more: Boolean,
    val offset: Int,
)
