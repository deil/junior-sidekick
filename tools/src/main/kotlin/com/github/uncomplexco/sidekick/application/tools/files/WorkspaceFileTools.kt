package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.nio.file.Path

@LLMDescription("General-purpose workspace file tools. Use global:/ for company-wide workspace files.")
class WorkspaceFileTools(
    globalRoot: Path,
) : ToolSet {
    private val files = WorkspaceFiles(globalRoot)

    @Tool
    @LLMDescription("Read a workspace file or directory. For company-wide workspace files, use a global:/ path.")
    fun workspaceFileRead(
        @LLMDescription("Workspace path to read.")
        path: String,
        @LLMDescription("The line or directory entry number to start reading from (1-indexed).")
        offset: Int? = null,
        @LLMDescription("The maximum number of lines or directory entries to read.")
        limit: Int? = null,
    ): String {
        val globalPath = parseGlobalPath(path)
        return files.read(globalPath.relative, offset, limit, globalPath.display)
    }

    @Tool
    @LLMDescription("Find workspace files by glob pattern. For company-wide workspace files, use a global:/ path prefix.")
    fun workspaceFileGlob(
        @LLMDescription("Glob pattern, for example **/*.md.")
        pattern: String,
        @LLMDescription("Workspace directory to search.")
        path: String,
    ): String {
        val globalPath = parseGlobalPath(path)
        return files.glob(pattern, globalPath.relative)
    }

    @Tool
    @LLMDescription("Search workspace text files by regex. For company-wide workspace files, use a global:/ path prefix.")
    fun workspaceFileGrep(
        @LLMDescription("Regex pattern to search for.")
        pattern: String,
        @LLMDescription("Workspace file or directory to search.")
        path: String,
        @LLMDescription("Optional include glob, for example **/*.md.")
        include: String? = null,
    ): String {
        val globalPath = parseGlobalPath(path)
        return files.grep(pattern, globalPath.relative, include)
    }

    private fun parseGlobalPath(path: String): GlobalPath {
        if (!path.startsWith(GLOBAL_PREFIX)) {
            throw ToolException.ValidationFailure("Only global:/ workspace paths are currently supported.")
        }

        val relative = path.removePrefix(GLOBAL_PREFIX).ifBlank { "." }
        if (relative.startsWith("/")) {
            throw ToolException.ValidationFailure("Invalid path: $path")
        }

        return GlobalPath(relative, path)
    }

    private data class GlobalPath(
        val relative: String,
        val display: String,
    )

    private companion object {
        private const val GLOBAL_PREFIX = "global:/"
    }
}
