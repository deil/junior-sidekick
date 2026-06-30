package com.github.uncomplexco.sidekick.application.agent

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = LoggerFactory.getLogger("sidekick.hooks")

fun onWorkCreated(
    config: AgentConfig,
    path: Path,
) {
    copyTemplate(config, "work", path)
}

fun onProjectCreated(
    config: AgentConfig,
    path: Path,
) {
    copyTemplate(config, "project", path)
}

private fun copyTemplate(
    config: AgentConfig,
    name: String,
    destination: Path,
) {
    try {
        val template = config.workingDirectoryPath().resolve("templates").resolve(name)
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
