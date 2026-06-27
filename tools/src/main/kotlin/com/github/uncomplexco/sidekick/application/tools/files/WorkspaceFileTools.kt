package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.DATA_ROOT
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_AGGREGATE_GLOB_RESULTS = 100
private const val MAX_AGGREGATE_GREP_RESULTS = 100

class WorkspaceFileTools(
    private val virtualPaths: VirtualPaths,
) : ToolSet {
    @Tool(TOOL_READ)
    @LLMDescription("Read a file or directory")
    fun workspaceFileRead(
        @LLMDescription("Absolute path to read")
        path: String,
        @LLMDescription("The line or directory entry number to start reading from (1-indexed)")
        offset: Int? = null,
        @LLMDescription("The maximum number of lines or directory entries to read")
        limit: Int? = null,
    ): String {
        if (path == DATA_ROOT) {
            return dataDirectory(offset, limit)
        }

        val workspacePath = resolveWorkspacePath(path)
        return WorkspaceFiles(workspacePath.root).read(workspacePath.relative, offset, limit, path)
    }

    @Tool(TOOL_GLOB)
    @LLMDescription("Find files by glob pattern")
    fun workspaceFileGlob(
        @LLMDescription("Glob pattern, for example **/*.md.")
        pattern: String,
        @LLMDescription("Directory to search. Absolute path")
        path: String,
    ): String {
        if (path == VIRTUAL_ROOT || path == DATA_ROOT) {
            return renderAggregateGlob(path, pattern)
        }

        val workspacePath = resolveWorkspacePath(path)
        return WorkspaceFiles(workspacePath.root).glob(pattern, workspacePath.relative)
    }

    @Tool(TOOL_GREP)
    @LLMDescription("Search text files by regex")
    fun workspaceFileGrep(
        @LLMDescription("Regex pattern to search for")
        pattern: String,
        @LLMDescription("File or directory to search. Absolute path")
        path: String,
        @LLMDescription("Optional include glob, for example **/*.md")
        include: String? = null,
    ): String {
        if (path == VIRTUAL_ROOT || path == DATA_ROOT) {
            return renderAggregateGrep(path, pattern, include)
        }

        val workspacePath = resolveWorkspacePath(path)
        return WorkspaceFiles(workspacePath.root).grep(pattern, workspacePath.relative, include)
    }

    private fun dataDirectory(
        offset: Int?,
        limit: Int?,
    ): String {
        if (offset != null && offset < 1) {
            throw ToolException.ValidationFailure("`offset` must be greater than or equal to 1")
        }

        val entries =
            dataRoots()
                .map { it.virtual.removePrefix("$DATA_ROOT/").substringBefore('/') + "/" }
                .distinct()
                .sorted()
        val currentOffset = offset ?: 1
        val currentLimit = limit ?: entries.size
        val start = currentOffset - 1
        val selected = entries.slice(start until minOf(start + currentLimit, entries.size))

        return listOf(
            "<path>$DATA_ROOT</path>",
            "<type>directory</type>",
            "<entries>",
            selected.joinToString("\n"),
            "\n(${entries.size} entries)",
            "</entries>",
        ).joinToString("\n")
    }

    private fun renderAggregateGlob(
        path: String,
        pattern: String,
    ): String {
        val rootResults =
            runBlocking {
                aggregateRoots(path)
                    .map { root -> async(Dispatchers.IO) { globRoot(root, pattern) } }
                    .awaitAll()
            }
        val matches =
            rootResults
                .flatMap { result -> result.matches }
                .sortedWith(compareByDescending<VirtualGlobMatch> { it.mtime }.thenBy { it.path })
        if (matches.isEmpty()) {
            return "No files found"
        }

        val truncated = rootResults.any { it.truncated } || matches.size > MAX_AGGREGATE_GLOB_RESULTS
        val final = if (truncated) matches.take(MAX_AGGREGATE_GLOB_RESULTS) else matches
        val output = final.map { it.path }.toMutableList()
        if (truncated) {
            output += ""
            output +=
                "(Results are truncated: showing first $MAX_AGGREGATE_GLOB_RESULTS results. Consider using a more specific path or pattern.)"
        }
        return output.joinToString("\n")
    }

    private fun renderAggregateGrep(
        path: String,
        pattern: String,
        include: String?,
    ): String {
        val rootResults =
            runBlocking {
                aggregateRoots(path)
                    .map { root -> async(Dispatchers.IO) { grepRoot(root, pattern, include) } }
                    .awaitAll()
            }
        val total = rootResults.sumOf { it.total }
        if (total == 0) {
            return "No files found"
        }

        val matches =
            rootResults
                .flatMap { it.matches }
                .sortedWith(compareByDescending<VirtualGrepMatch> { it.mtime }.thenBy { it.path })
        val truncated = total > MAX_AGGREGATE_GREP_RESULTS
        val final = if (truncated) matches.take(MAX_AGGREGATE_GREP_RESULTS) else matches
        val output = mutableListOf("Found $total matches${if (truncated) " (showing first $MAX_AGGREGATE_GREP_RESULTS)" else ""}")

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
                "(Results truncated: showing $MAX_AGGREGATE_GREP_RESULTS of $total matches (${total - MAX_AGGREGATE_GREP_RESULTS} hidden). Consider using a more specific path or pattern.)"
        }

        return output.joinToString("\n")
    }

    private fun globRoot(
        root: WorkspaceRoot,
        pattern: String,
    ): VirtualGlobMatches {
        if (!Files.isDirectory(root.real)) {
            return VirtualGlobMatches(emptyList(), truncated = false)
        }

        val result = WorkspaceFiles(root.real).globMatches(pattern, ".")
        return VirtualGlobMatches(
            matches = result.matches.map { VirtualGlobMatch("${root.virtual}/${it.path}", it.mtime) },
            truncated = result.truncated,
        )
    }

    private fun grepRoot(
        root: WorkspaceRoot,
        pattern: String,
        include: String?,
    ): VirtualGrepMatches {
        if (!Files.isDirectory(root.real)) {
            return VirtualGrepMatches(emptyList(), total = 0)
        }

        val result = WorkspaceFiles(root.real).grepMatches(pattern, ".", include)
        return VirtualGrepMatches(
            matches =
                result.matches.map {
                    VirtualGrepMatch(
                        path = "${root.virtual}/${root.real.relativize(Path.of(it.path)).toString().replace('\\', '/')}",
                        line = it.line,
                        text = it.text,
                        mtime = it.mtime,
                    )
                },
            total = result.total,
        )
    }

    private fun resolveWorkspacePath(path: String): WorkspacePath =
        try {
            val realPath = Path.of(parseVirtualPath(path, virtualPaths))
            val root = rootFor(realPath) ?: throw ToolException.ValidationFailure("Path not found: $path")
            WorkspacePath(root, root.relativize(realPath).toString().ifBlank { "." })
        } catch (_: IllegalStateException) {
            throw ToolException.ValidationFailure("Path not found: $path")
        }

    private fun rootFor(realPath: Path): Path? =
        virtualPaths.roots
            .map { it.real }
            .firstOrNull { realPath == it || realPath.startsWith(it) }

    private fun dataRoots(): List<WorkspaceRoot> =
        virtualPaths.roots
            .filter { it.virtual.startsWith("$DATA_ROOT/") }
            .map { WorkspaceRoot(it.virtual, it.real) }

    private fun knownRoots(): List<WorkspaceRoot> = virtualPaths.roots.map { WorkspaceRoot(it.virtual, it.real) }

    private fun aggregateRoots(path: String): List<WorkspaceRoot> =
        when (path) {
            VIRTUAL_ROOT -> knownRoots()
            DATA_ROOT -> dataRoots()
            else -> error("Unsupported aggregate root: $path")
        }

    private data class WorkspaceRoot(
        val virtual: String,
        val real: Path,
    )

    private data class VirtualGlobMatch(
        val path: String,
        val mtime: Long,
    )

    private data class VirtualGlobMatches(
        val matches: List<VirtualGlobMatch>,
        val truncated: Boolean,
    )

    private data class VirtualGrepMatch(
        val path: String,
        val line: Int,
        val text: String,
        val mtime: Long,
    )

    private data class VirtualGrepMatches(
        val matches: List<VirtualGrepMatch>,
        val total: Int,
    )

    private data class WorkspacePath(
        val root: Path,
        val relative: String,
    )

    companion object {
        const val TOOL_GLOB = "fs__glob"
        const val TOOL_READ = "fs__read"
        const val TOOL_GREP = "fs__grep"

        private const val VIRTUAL_ROOT = "/"
    }
}
