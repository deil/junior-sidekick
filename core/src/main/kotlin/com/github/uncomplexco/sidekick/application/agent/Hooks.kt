package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.adapters.files.seedDirectory
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("sidekick.hooks")

fun onWorkCreated(
    config: AgentConfig,
    path: Path,
) {
    copyTemplate(config, "work", path)
}

fun onProjectCreated(
    config: AgentConfig,
    projectId: String,
    filesystem: WorkspaceFilesystem,
) {
    val template = config.workspaceLayout().templatesDirectoryPath().resolve("project")
    if (!Files.isDirectory(template)) return

    try {
        runBlocking { filesystem.seedProject(projectId, template) }
    } catch (error: Exception) {
        logger.warn("Failed to seed project workspace project_id={}", projectId, error)
    }
}

private fun copyTemplate(
    config: AgentConfig,
    name: String,
    destination: Path,
) {
    try {
        val template = config.workspaceLayout().templatesDirectoryPath().resolve(name)
        if (!Files.isDirectory(template)) {
            return
        }

        seedDirectory(template, destination)
    } catch (error: Exception) {
        logger.warn("Failed to copy workspace template '{}' into {}", name, destination, error)
    }
}
