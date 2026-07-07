package com.github.uncomplexco.sidekick.application.agent.workspace

import java.nio.file.Files
import java.nio.file.Path

class WorkspaceLayout(
    private val root: Path,
) {
    fun configDirectoryPath(): Path = directory("config")

    fun templatesDirectoryPath(): Path = directory("templates")

    fun sessionWorkspacesDirectoryPath(): Path = directory("data/workspaces/threads")

    fun projectWorkspacesDirectoryPath(): Path = directory("data/workspaces/projects")

    fun extensionsConfigPath(): Path = configDirectoryPath().resolve("extensions.json")

    fun knowledgeConfigPath(): Path = configDirectoryPath().resolve("knowledge.json")

    fun extensionsRepositoryDirectoryPath(): Path = directory("data/repositories/extensions")

    fun knowledgeRepositoryDirectoryPath(): Path = directory("data/repositories/knowledge")

    private fun directory(relativePath: String): Path {
        val path = root.resolve(relativePath).toAbsolutePath().normalize()
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent workspace path is not a directory: $path" }
        return path
    }
}
