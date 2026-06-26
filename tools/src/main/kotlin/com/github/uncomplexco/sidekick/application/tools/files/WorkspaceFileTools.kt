package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import java.nio.file.Path

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
        val workspacePath = resolveWorkspacePath(path)
        return WorkspaceFiles(workspacePath.root).grep(pattern, workspacePath.relative, include)
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
        listOf(virtualPaths.sessionRoot, virtualPaths.skillsRoot, virtualPaths.globalRoot, virtualPaths.workRoot)
            .firstOrNull { realPath == it || realPath.startsWith(it) }

    private data class WorkspacePath(
        val root: Path,
        val relative: String,
    )

    companion object {
        const val TOOL_GLOB = "fs__glob"
        const val TOOL_READ = "fs__read"
        const val TOOL_GREP = "fs__grep"
    }
}
