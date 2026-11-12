package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

        Files.walk(template).use { paths ->
            paths.forEach { source ->
                val relative = template.relativize(source)
                if (relative.toString().isBlank()) {
                    return@forEach
                }

                val target = destination.resolve(relative)
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else if (!Files.exists(target)) {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    } catch (error: Exception) {
        logger.warn("Failed to copy workspace template '{}' into {}", name, destination, error)
    }
}
