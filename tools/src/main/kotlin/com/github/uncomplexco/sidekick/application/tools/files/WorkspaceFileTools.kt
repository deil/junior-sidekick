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
        try {
            return WorkspaceRead(virtualPaths).read(path, offset, limit)
        } catch (e: IllegalArgumentException) {
            throw ToolException.ValidationFailure(e.message ?: "Tool invocation failed")
        }
    }

    @Tool(TOOL_GLOB)
    @LLMDescription("Find files by glob pattern")
    fun workspaceFileGlob(
        @LLMDescription("Glob pattern, for example **/*.md.")
        pattern: String,
        @LLMDescription("Directory to search. Absolute path")
        path: String,
    ): String {
        val normalizedPath = normalizeVirtualDirectoryPath(path)
        if (normalizedPath == VIRTUAL_ROOT || normalizedPath == DATA_ROOT) {
            val roots =
                when (normalizedPath) {
                    VIRTUAL_ROOT -> virtualPaths.roots
                    DATA_ROOT -> virtualPaths.roots.filter { it.virtual.startsWith("$DATA_ROOT/") }
                    else -> error("Unsupported aggregate root: $normalizedPath")
                }
            val rootResults =
                runBlocking {
                    roots
                        .map { root ->
                            async(Dispatchers.IO) {
                                if (!Files.isDirectory(root.real)) {
                                    VirtualGlobMatches(emptyList(), truncated = false)
                                } else {
                                    val result = WorkspaceFiles(root.real).globMatches(pattern, ".")
                                    VirtualGlobMatches(
                                        matches = result.matches.map { VirtualGlobMatch("${root.virtual}/${it.path}", it.mtime) },
                                        truncated = result.truncated,
                                    )
                                }
                            }
                        }.awaitAll()
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

        val workspacePath = resolveWorkspacePath(normalizedPath)
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
        val normalizedPath = normalizeVirtualDirectoryPath(path)
        if (normalizedPath == VIRTUAL_ROOT || normalizedPath == DATA_ROOT) {
            val roots =
                when (normalizedPath) {
                    VIRTUAL_ROOT -> virtualPaths.roots
                    DATA_ROOT -> virtualPaths.roots.filter { it.virtual.startsWith("$DATA_ROOT/") }
                    else -> error("Unsupported aggregate root: $normalizedPath")
                }
            val rootResults =
                runBlocking {
                    roots
                        .map { root ->
                            async(Dispatchers.IO) {
                                if (!Files.isDirectory(root.real)) {
                                    VirtualGrepMatches(emptyList(), total = 0)
                                } else {
                                    val result = WorkspaceFiles(root.real).grepMatches(pattern, ".", include)
                                    VirtualGrepMatches(
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
                            }
                        }.awaitAll()
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

        val workspacePath = resolveWorkspacePath(normalizedPath)
        return WorkspaceFiles(workspacePath.root).grep(pattern, workspacePath.relative, include)
    }

    private fun normalizeVirtualDirectoryPath(path: String): String = if (path == VIRTUAL_ROOT) path else path.trimEnd('/')

    private fun resolveWorkspacePath(path: String): WorkspacePath =
        try {
            val realPath = Path.of(parseVirtualPath(path, virtualPaths))
            val root =
                virtualPaths.roots
                    .firstOrNull { path == it.virtual || path.startsWith("${it.virtual}/") }
                    ?.real
                    ?: throw ToolException.ValidationFailure("Path not found: $path")
            WorkspacePath(root, root.relativize(realPath).toString().ifBlank { "." })
        } catch (_: IllegalStateException) {
            throw ToolException.ValidationFailure("Path not found: $path")
        }

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
        const val TOOL_GLOB = "Glob"
        const val TOOL_READ = "Read"
        const val TOOL_GREP = "Grep"

        private const val VIRTUAL_ROOT = "/"
    }
}
