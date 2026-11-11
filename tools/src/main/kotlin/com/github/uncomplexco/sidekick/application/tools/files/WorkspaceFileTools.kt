package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.DATA_ROOT
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import kotlinx.coroutines.runBlocking

class WorkspaceFileTools(
    private val projectId: String,
    private val filesystem: WorkspaceFilesystem,
) : ToolSet {
    @Tool(TOOL_READ)
    @LLMDescription("Read a file or directory")
    fun workspaceFileRead(
        @LLMDescription("Absolute path to read") path: String,
        @LLMDescription("The line or directory entry number to start reading from (1-indexed)")
        offset: Int? = null,
        @LLMDescription("The maximum number of lines or directory entries to read")
        limit: Int? = null,
    ): String {
        try {
            return WorkspaceRead(projectId, filesystem).read(path, offset, limit)
        } catch (e: IllegalArgumentException) {
            throw ToolException.ValidationFailure(e.message ?: "Tool invocation failed")
        }
    }

    @Tool(TOOL_GLOB)
    @LLMDescription("Find files by glob pattern")
    fun workspaceFileGlob(
        @LLMDescription("Glob pattern, for example **/*.md.") pattern: String,
        @LLMDescription("Directory to search. Absolute path") path: String,
    ): String {
        val normalizedPath = normalizeVirtualDirectoryPath(path)
        val result = runBlocking { filesystem.glob(projectId, normalizedPath, pattern) }
        if (result.matches.isEmpty()) {
            return "No files found"
        }

        val aggregate = normalizedPath == VIRTUAL_ROOT || normalizedPath == DATA_ROOT
        val output =
            result.matches
                .map {
                    if (aggregate) it.path else it.path.removePrefix("$normalizedPath/")
                }
                .toMutableList()

        if (result.truncated) {
            output += ""
            output +=
                "(Results are truncated: showing first $MAX_AGGREGATE_GLOB_RESULTS results. Consider using a more specific path or pattern.)"
        }

        return output.joinToString("\n")
    }

    @Tool(TOOL_GREP)
    @LLMDescription("Search text files by regex")
    fun workspaceFileGrep(
        @LLMDescription("Regex pattern to search for") pattern: String,
        @LLMDescription("File or directory to search. Absolute path") path: String,
        @LLMDescription("Optional include glob, for example **/*.md") include: String? = null,
    ): String {
        val normalizedPath = normalizeVirtualDirectoryPath(path)
        val result = runBlocking { filesystem.grep(projectId, normalizedPath, pattern, include) }
        if (result.matches.isEmpty()) {
            return "No files found"
        }

        val output =
            mutableListOf(
                "Found ${result.total} matches" +
                    if (result.truncated) " (showing first $MAX_AGGREGATE_GREP_RESULTS)" else ""
            )

        var current = ""
        for (match in result.matches) {
            if (current != match.path) {
                if (current.isNotEmpty()) output += ""
                current = match.path
                output += "${match.path}:"
            }

            val text =
                if (match.text.length > MAX_GREP_LINE_LENGTH) {
                    match.text.take(MAX_GREP_LINE_LENGTH) + "..."
                } else match.text
            output += "  Line ${match.line}: $text"
        }

        if (result.truncated) {
            output += ""
            output +=
                "(Results truncated: showing $MAX_AGGREGATE_GREP_RESULTS of ${result.total} matches (${result.total - MAX_AGGREGATE_GREP_RESULTS} hidden). Consider using a more specific path or pattern.)"
        }

        return output.joinToString("\n")
    }

    private fun normalizeVirtualDirectoryPath(path: String): String =
        if (path == VIRTUAL_ROOT) path else path.trimEnd('/')

    companion object {
        private const val MAX_AGGREGATE_GLOB_RESULTS = 100
        private const val MAX_AGGREGATE_GREP_RESULTS = 100
        private const val MAX_GREP_LINE_LENGTH = 2000

        const val TOOL_GLOB = "Glob"
        const val TOOL_READ = "Read"
        const val TOOL_GREP = "Grep"

        private const val VIRTUAL_ROOT = "/"
    }
}
