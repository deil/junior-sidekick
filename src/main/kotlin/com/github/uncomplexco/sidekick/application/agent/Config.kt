package com.github.uncomplexco.sidekick.application.agent

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

@Configuration
class AgentConfig(
    @Value($$"${agent.name}") val name: String,
    @Value($$"${agent.state-directory}") val stateDir: String,
) {
    fun stateDirectoryPath(): Path {
        val path = Path.of(stateDir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent state directory is not a directory: $path" }
        return path
    }
}
