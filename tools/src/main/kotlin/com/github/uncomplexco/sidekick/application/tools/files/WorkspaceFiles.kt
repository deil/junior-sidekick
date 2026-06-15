package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.StandardOpenOption
import kotlin.collections.asSequence
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.streams.asSequence
import kotlin.use

private const val DEFAULT_READ_LIMIT = 2000
private const val MAX_GLOB_RESULTS = 100
private const val MAX_GREP_RESULTS = 100
private const val MAX_LINE_LENGTH = 2000
private const val MAX_LINE_SUFFIX = "... (line truncated to 2000 chars)"
private const val MAX_BYTES = 50 * 1024
private const val MAX_BYTES_LABEL = "50 KB"

private sealed interface PatchOperation {
    data class AddFile(
        val path: String,
        val content: String,
    ) : PatchOperation

    data class DeleteFile(
        val path: String,
    ) : PatchOperation

    data class UpdateFile(
        val path: String,
        val moveTo: String?,
        val diffLines: List<String>,
    ) : PatchOperation
}

private data class ReadResult(
    val raw: List<String>,
    val count: Int,
    val cut: Boolean,
    val more: Boolean,
    val offset: Int,
)

private data class Match(
    val path: String,
    val matches: Boolean,
    val mtime: Long,
)

private data class WalkedFile(
    val actual: Path,
    val display: Path,
    val relative: Path,
)

private data class GrepMatch(
    val path: String,
    val line: Int,
    val text: String,
    val mtime: Long,
)

private fun parseApplyPatch(patchText: String): List<PatchOperation> {
    val lines = patchText.lines()
    if (lines.firstOrNull() != "*** Begin Patch" || lines.lastOrNull() != "*** End Patch") {
        throw IllegalArgumentException("apply_patch verification failed: invalid patch envelope")
    }

    val operations = mutableListOf<PatchOperation>()
    var index = 1
    while (index < lines.lastIndex) {
        val line = lines[index]
        when {
            line.startsWith("*** Add File: ") -> {
                val path = line.removePrefix("*** Add File: ").trim()
                index += 1
                val content = mutableListOf<String>()
                while (index < lines.lastIndex && !lines[index].startsWith("*** ")) {
                    val current = lines[index]
                    if (!current.startsWith("+")) {
                        throw IllegalArgumentException("apply_patch verification failed: invalid add file line: $current")
                    }
                    content += current.removePrefix("+")
                    index += 1
                }
                operations +=
                    PatchOperation.AddFile(
                        path,
                        if (content.isEmpty()) "" else content.joinToString("\n") + "\n",
                    )
            }

            line.startsWith("*** Delete File: ") -> {
                val path = line.removePrefix("*** Delete File: ").trim()
                operations += PatchOperation.DeleteFile(path)
                index += 1
            }

            line.startsWith("*** Update File: ") -> {
                val path = line.removePrefix("*** Update File: ").trim()
                index += 1
                var moveTo: String? = null
                if (index < lines.lastIndex && lines[index].startsWith("*** Move to: ")) {
                    moveTo = lines[index].removePrefix("*** Move to: ").trim()
                    index += 1
                }
                val diff = mutableListOf<String>()
                while (index < lines.lastIndex && !lines[index].startsWith("*** ")) {
                    diff += lines[index]
                    index += 1
                }
                if (diff.isEmpty()) {
                    throw IllegalArgumentException("apply_patch verification failed: no hunks found")
                }
                operations += PatchOperation.UpdateFile(path, moveTo, diff)
            }

            line.isBlank() -> {
                index += 1
            }

            else -> {
                throw IllegalArgumentException("apply_patch verification failed: invalid patch line: $line")
            }
        }
    }

    return operations
}

private fun applyUnifiedUpdate(
    source: Path,
    original: String,
    operation: PatchOperation.UpdateFile,
): String {
    var content = original
    val hunks = splitPatchHunks(operation.diffLines)
    if (hunks.isEmpty()) {
        throw IllegalArgumentException("apply_patch verification failed: no hunks found")
    }

    for (hunk in hunks) {
        val oldBlock = hunk.filter { it.startsWith("-") || it.startsWith(" ") }.joinToString("\n") { it.drop(1) }
        val newBlock = hunk.filter { it.startsWith("+") || it.startsWith(" ") }.joinToString("\n") { it.drop(1) }
        content =
            when {
                oldBlock.isEmpty() -> insertByContext(content, hunk, newBlock)
                content.contains(oldBlock) -> content.replaceFirst(oldBlock, newBlock)
                else -> throw IllegalArgumentException("apply_patch verification failed: Failed to apply update to ${source.pathString}")
            }
    }

    return content.ensureTrailingNewlineIfNeeded(operation.diffLines)
}

private fun splitPatchHunks(diffLines: List<String>): List<List<String>> {
    val hunks = mutableListOf<MutableList<String>>()
    var current: MutableList<String>? = null
    for (line in diffLines) {
        when {
            line.startsWith("@@") -> {
                current = mutableListOf()
                hunks += current
            }

            current != null -> {
                current += line
            }
        }
    }
    return hunks
}

private fun insertByContext(
    content: String,
    hunk: List<String>,
    newBlock: String,
): String {
    val contextLines = hunk.filter { it.startsWith(" ") }.map { it.drop(1) }
    if (contextLines.isEmpty()) {
        return content + newBlock
    }

    val joinedContent = content.split("\n")
    val anchor = contextLines.joinToString("\n")
    val index = joinedContent.joinToString("\n").indexOf(anchor)
    if (index < 0) {
        throw IllegalArgumentException("apply_patch verification failed: Failed to apply insert hunk")
    }
    val anchorEnd = index + anchor.length
    return content.substring(0, anchorEnd) + "\n" + newBlock + content.substring(anchorEnd)
}

private fun String.ensureTrailingNewlineIfNeeded(diffLines: List<String>): String {
    val hasAddedLine = diffLines.any { it.startsWith("+") }
    if (!hasAddedLine || endsWith("\n")) {
        return this
    }
    return "$this\n"
}

private fun replaceOrThrow(
    content: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean,
): String {
    if (oldString.isEmpty()) {
        return newString
    }
    val occurrences = Regex(Regex.escape(oldString)).findAll(content).count()
    if (occurrences == 0) {
        throw IllegalArgumentException("oldString not found in file")
    }
    if (!replaceAll && occurrences > 1) {
        throw IllegalArgumentException("Found multiple matches for oldString. Provide more surrounding context to make the match unique.")
    }
    return if (replaceAll) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
}

class WorkspaceFiles(
    root: Path,
) {
    private val root = root.toAbsolutePath().normalize()
    private val realRoot =
        root
            .also { require(!Files.isSymbolicLink(it)) { "Workspace root must not be a symbolic link: $it" } }
            .toRealPath(LinkOption.NOFOLLOW_LINKS)

    fun read(
        filePath: String,
        offset: Int?,
        limit: Int?,
        displayPath: String? = null,
    ): String {
        if (offset != null && offset < 1) {
            throw ToolException.ValidationFailure("`offset` must be greater than or equal to 1")
        }

        val target = resolve(filePath)
        if (!Files.exists(target)) {
            throw ToolException.ValidationFailure(miss(target))
        }

        val displayTarget = displayPath ?: target.pathString
        if (Files.isDirectory(target)) {
            val items = Files.list(target).use { stream -> stream.toList().map { entryName(it) }.sorted() }
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

        if (isBinaryFile(target)) {
            throw ToolException.ValidationFailure("Cannot read binary file: ${target.pathString}")
        }

        val file = lines(target, limit ?: DEFAULT_READ_LIMIT, offset ?: 1)
        if (file.count < file.offset && !(file.count == 0 && file.offset == 1)) {
            throw ToolException.ValidationFailure("Offset ${file.offset} is out of range for this file (${file.count} lines)")
        }

        var output = listOf("<path>$displayTarget</path>", "<type>file</type>", "<content>\n").joinToString("\n")
        output += file.raw.mapIndexed { index, line -> "${index + file.offset}: $line" }.joinToString("\n")

        val last = file.offset + file.raw.size - 1
        val next = last + 1
        if (file.cut) {
            output += "\n\n(Output capped at $MAX_BYTES_LABEL. Showing lines ${file.offset}-$last. Use offset=$next to continue.)"
        } else if (file.more) {
            output += "\n\n(Showing lines ${file.offset}-$last of ${file.count}. Use offset=$next to continue.)"
        } else {
            output += "\n\n(End of file - total ${file.count} lines)"
        }
        output += "\n</content>"
        return output
    }

    fun glob(
        pattern: String,
        path: String?,
    ): String {
        val search = resolve(path)
        if (Files.exists(search) && Files.isRegularFile(search)) {
            throw IllegalArgumentException("glob path must be a directory: ${search.pathString}")
        }

        val matchers = globMatchers(search, pattern)
        val files =
            walkFiles(search)
                .asSequence()
                .filter { matchesGlob(matchers, it.relative) }
                .map { file -> Match(file.relative.pathString, true, Files.getLastModifiedTime(file.actual).toMillis()) }
                .sortedWith(compareByDescending<Match> { it.mtime }.thenBy { it.path })
                .take(MAX_GLOB_RESULTS + 1)
                .toList()

        val truncated = files.size > MAX_GLOB_RESULTS
        val final = if (truncated) files.take(MAX_GLOB_RESULTS) else files

        val output = mutableListOf<String>()
        if (final.isEmpty()) {
            output += "No files found"
        } else {
            output += final.map { it.path }
            if (truncated) {
                output += ""
                output +=
                    "(Results are truncated: showing first $MAX_GLOB_RESULTS results. Consider using a more specific path or pattern.)"
            }
        }
        return output.joinToString("\n")
    }

    fun grep(
        pattern: String,
        path: String?,
        include: String?,
    ): String {
        if (pattern.isBlank()) {
            throw IllegalArgumentException("pattern is required")
        }

        val search = resolve(path)
        val regex = Regex(pattern)
        val info = if (Files.exists(search)) search else null
        val cwd =
            if (info != null && Files.isDirectory(info)) {
                info
            } else {
                search.parent
                    ?: throw IllegalArgumentException("Path not found: ${search.pathString}")
            }
        val file = if (info != null && Files.isRegularFile(info)) listOf(search.fileName) else null
        val includeMatchers = include?.takeIf { it.isNotBlank() }?.let { globMatchers(cwd, it) }

        val rows =
            candidateFiles(cwd, file, includeMatchers)
                .flatMap { item ->
                    if (isBinaryFile(item.actual)) {
                        return@flatMap emptyList()
                    }
                    val mtime = Files.getLastModifiedTime(item.actual).toMillis()
                    runCatching {
                        Files.readAllLines(item.actual, StandardCharsets.UTF_8).mapIndexedNotNull { index, text ->
                            if (!regex.containsMatchIn(text)) {
                                null
                            } else {
                                GrepMatch(item.display.pathString, index + 1, clipForGrep(text), mtime)
                            }
                        }
                    }.getOrElse { error ->
                        if (error is MalformedInputException) {
                            emptyList()
                        } else {
                            throw error
                        }
                    }
                }.sortedWith(compareByDescending<GrepMatch> { it.mtime }.thenBy { it.path })

        if (rows.isEmpty()) {
            return "No files found"
        }

        val truncated = rows.size > MAX_GREP_RESULTS
        val final = if (truncated) rows.take(MAX_GREP_RESULTS) else rows
        val output = mutableListOf("Found ${rows.size} matches${if (truncated) " (showing first $MAX_GREP_RESULTS)" else ""}")

        var current = ""
        for (match in final) {
            if (current != match.path) {
                if (current.isNotEmpty()) {
                    output += ""
                }
                current = match.path
                output += "${match.path}:"
            }
            output += "  Line ${match.line}: ${match.text}"
        }

        if (truncated) {
            output += ""
            output +=
                "(Results truncated: showing $MAX_GREP_RESULTS of ${rows.size} matches (${rows.size - MAX_GREP_RESULTS} hidden). Consider using a more specific path or pattern.)"
        }

        return output.joinToString("\n")
    }

    fun write(
        filePath: String,
        content: String,
    ): String {
        val target = resolve(filePath)
        val existed = Files.exists(target)
        if (existed && Files.isDirectory(target)) {
            throw IllegalArgumentException("Path is a directory, not a file: ${target.pathString}")
        }

        writeText(target, content)
        return "Wrote file successfully."
    }

    fun edit(
        filePath: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): String {
        if (filePath.isBlank()) {
            throw IllegalArgumentException("filePath is required")
        }
        if (oldString == newString) {
            throw IllegalArgumentException("No changes to apply: oldString and newString are identical.")
        }

        val target = resolve(filePath)
        if (oldString.isEmpty()) {
            writeText(target, newString)
            return "Edit applied successfully."
        }

        if (!Files.exists(target)) {
            throw IllegalArgumentException("File ${target.pathString} not found")
        }
        if (Files.isDirectory(target)) {
            throw IllegalArgumentException("Path is a directory, not a file: ${target.pathString}")
        }

        val original = readUtf8(target)
        val updated = replaceOrThrow(original, oldString, newString, replaceAll)
        writeText(target, updated)
        return "Edit applied successfully."
    }

    fun applyPatch(patchText: String): String {
        if (patchText.isEmpty()) {
            throw IllegalArgumentException("patchText is required")
        }

        val normalized = patchText.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized == "*** Begin Patch\n*** End Patch") {
            throw IllegalArgumentException("patch rejected: empty patch")
        }

        val operations = parseApplyPatch(normalized)
        if (operations.isEmpty()) {
            throw IllegalArgumentException("apply_patch verification failed: no hunks found")
        }

        val summaries = mutableListOf<String>()
        for (operation in operations) {
            when (operation) {
                is PatchOperation.AddFile -> {
                    val target = resolve(operation.path)
                    writeText(target, operation.content)
                    summaries += "A ${relativeDisplay(target)}"
                }

                is PatchOperation.DeleteFile -> {
                    val target = resolve(operation.path)
                    if (!Files.exists(target) || Files.isDirectory(target)) {
                        throw IllegalArgumentException(
                            "apply_patch verification failed: Failed to read file to update: ${target.pathString}",
                        )
                    }
                    Files.delete(target)
                    summaries += "D ${relativeDisplay(target)}"
                }

                is PatchOperation.UpdateFile -> {
                    val source = resolve(operation.path)
                    if (!Files.exists(source) || Files.isDirectory(source)) {
                        throw IllegalArgumentException(
                            "apply_patch verification failed: Failed to read file to update: ${source.pathString}",
                        )
                    }
                    val original = readUtf8(source)
                    val updated = applyUnifiedUpdate(source, original, operation)
                    val destination = operation.moveTo?.let(::resolve)
                    if (destination != null) {
                        writeText(destination, updated)
                        Files.delete(source)
                        summaries += "M ${relativeDisplay(destination)}"
                    } else {
                        writeText(source, updated)
                        summaries += "M ${relativeDisplay(source)}"
                    }
                }
            }
        }

        return "Success. Updated the following files:\n${summaries.joinToString("\n")}"
    }

    private fun candidateFiles(
        cwd: Path,
        file: List<Path>?,
        includeMatchers: List<PathMatcher>?,
    ): List<WalkedFile> {
        if (file != null) {
            val target = cwd.resolve(file.first()).normalize()
            val relative = cwd.relativize(target)
            if (includeMatchers != null && !matchesGlob(includeMatchers, relative)) {
                return emptyList()
            }
            return listOf(WalkedFile(target, target, relative))
        }

        return walkFiles(cwd).filter { includeMatchers == null || matchesGlob(includeMatchers, it.relative) }
    }

    private fun walkFiles(root: Path): List<WalkedFile> {
        val base = containedRealPath(root)
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { !containsGitDir(it) }
                .map { file ->
                    val actual = containedRealPath(file)
                    val relative = base.relativize(actual)
                    val display = root.resolve(relative).normalize()
                    WalkedFile(actual, display, relative)
                }.toList()
        }
    }

    private fun resolve(input: String?): Path {
        val value = input?.ifBlank { "." } ?: "."
        val path = root.resolve(value).normalize()
        require(path.startsWith(root)) { "Path escapes working directory: $input" }
        rejectSymbolicPath(path)
        if (Files.exists(path)) {
            containedRealPath(path)
        }
        return path
    }

    private fun containedRealPath(path: Path): Path {
        val realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(realPath.startsWith(realRoot)) { "Path escapes working directory: $path" }
        return realPath
    }

    private fun rejectSymbolicPath(path: Path) {
        val relative = root.relativize(path)
        var current = root
        for (segment in relative) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw IllegalArgumentException("Symbolic links are not allowed in workspace paths: $current")
            }
        }
    }

    private fun miss(path: Path): String {
        val dir = path.parent ?: root
        val base = path.fileName?.toString().orEmpty()
        val items =
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { stream ->
                    stream
                        .asSequence()
                        .map { it.fileName.toString() }
                        .filter { item -> item.contains(base, ignoreCase = true) || base.contains(item, ignoreCase = true) }
                        .take(3)
                        .map { name -> dir.resolve(name).pathString }
                        .toList()
                }
            } else {
                emptyList<String>()
            }

        if (items.isNotEmpty()) {
            return "File not found: ${path.pathString}\n\nDid you mean one of these?\n${items.joinToString("\n")}"
        }

        return "File not found: ${path.pathString}"
    }

    private fun entryName(path: Path): String = if (Files.isDirectory(path)) path.name + "/" else path.name

    private fun writeText(
        path: Path,
        content: String,
    ) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun readUtf8(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    private fun relativeDisplay(path: Path): String = root.relativize(path).pathString.replace("\\", "/")

    private fun clipForRead(text: String): String =
        if (text.length > MAX_LINE_LENGTH) text.substring(0, MAX_LINE_LENGTH) + MAX_LINE_SUFFIX else text

    private fun clipForGrep(text: String): String = if (text.length > MAX_LINE_LENGTH) text.substring(0, MAX_LINE_LENGTH) + "..." else text

    private fun containsGitDir(path: Path): Boolean = path.normalize().asSequence().any { it.toString() == ".git" }

    private fun matchesGlob(
        matchers: List<PathMatcher>,
        relative: Path,
    ): Boolean = matchers.any { matcher -> matcher.matches(relative) || (relative.nameCount == 1 && matcher.matches(relative.fileName)) }

    private fun globMatchers(
        base: Path,
        pattern: String,
    ): List<PathMatcher> {
        val patterns = linkedSetOf(pattern)
        if (pattern.startsWith("**/")) {
            patterns += pattern.removePrefix("**/")
        }
        return patterns.map { base.fileSystem.getPathMatcher("glob:$it") }
    }

    private fun isBinaryFile(path: Path): Boolean {
        val ext =
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        if (ext in
            setOf(
                "zip",
                "tar",
                "gz",
                "exe",
                "dll",
                "so",
                "class",
                "jar",
                "war",
                "7z",
                "doc",
                "docx",
                "xls",
                "xlsx",
                "ppt",
                "pptx",
                "odt",
                "ods",
                "odp",
                "bin",
                "dat",
                "obj",
                "o",
                "a",
                "lib",
                "wasm",
                "pyc",
                "pyo",
            )
        ) {
            return true
        }

        val size = Files.size(path)
        if (size == 0L) {
            return false
        }

        val sample = Files.newInputStream(path).use { it.readNBytes(minOf(4096, size.toInt())) }
        var nonPrintable = 0
        for (byte in sample) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                return true
            }
            if (value < 9 || (value > 13 && value < 32)) {
                nonPrintable++
            }
        }
        return nonPrintable.toDouble() / sample.size > 0.3
    }

    private fun lines(
        path: Path,
        limit: Int,
        offset: Int,
    ): ReadResult {
        val start = offset - 1
        val raw = mutableListOf<String>()
        var bytes = 0
        var count = 0
        var cut = false
        var more = false

        Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
            for (text in lines) {
                count += 1
                if (count <= start) {
                    continue
                }

                if (raw.size >= limit) {
                    more = true
                    continue
                }

                val line = clipForRead(text)
                val size = line.toByteArray(StandardCharsets.UTF_8).size + if (raw.isNotEmpty()) 1 else 0
                if (bytes + size > MAX_BYTES) {
                    cut = true
                    more = true
                    break
                }

                raw += line
                bytes += size
            }
        }

        return ReadResult(raw, count, cut, more, offset)
    }
}
