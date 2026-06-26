package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
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
            return runBlocking {
                aggregateRoots(path)
                    .map { root -> async(Dispatchers.IO) { globRoot(root, pattern) } }
                    .awaitAll()
            }.flatten()
                .ifEmpty { listOf("No files found") }
                .joinToString("\n")
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
            return runBlocking {
                aggregateRoots(path)
                    .map { root -> async(Dispatchers.IO) { grepRoot(root, pattern, include) } }
                    .awaitAll()
            }.filterNotNull()
                .ifEmpty { listOf("No files found") }
                .joinToString("\n\n")
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

        val entries = listOf("session/", "skills/", "global/")
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

    private fun globRoot(
        root: WorkspaceRoot,
        pattern: String,
    ): List<String> {
        if (!Files.isDirectory(root.real)) {
            return emptyList()
        }

        return WorkspaceFiles(root.real)
            .glob(pattern, ".")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("No files found") && !it.startsWith("(") }
            .map { "${root.virtual}/$it" }
    }

    private fun grepRoot(
        root: WorkspaceRoot,
        pattern: String,
        include: String?,
    ): String? {
        if (!Files.isDirectory(root.real)) {
            return null
        }

        val result = WorkspaceFiles(root.real).grep(pattern, ".", include)
        if (result == "No files found") {
            return null
        }

        return result.replace(root.real.toString(), root.virtual)
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

    private fun dataRoots(): List<WorkspaceRoot> =
        listOf(
            WorkspaceRoot(VirtualPaths.SESSION_ROOT, virtualPaths.sessionRoot),
            WorkspaceRoot(VirtualPaths.SKILLS_ROOT, virtualPaths.skillsRoot),
            WorkspaceRoot(VirtualPaths.GLOBAL_ROOT, virtualPaths.globalRoot),
        )

    private fun knownRoots(): List<WorkspaceRoot> = dataRoots() + WorkspaceRoot(VirtualPaths.WORK_ROOT, virtualPaths.workRoot)

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

    private data class WorkspacePath(
        val root: Path,
        val relative: String,
    )

    companion object {
        const val TOOL_GLOB = "fs__glob"
        const val TOOL_READ = "fs__read"
        const val TOOL_GREP = "fs__grep"

        private const val VIRTUAL_ROOT = "/"
        private const val DATA_ROOT = "/data"
    }
}
