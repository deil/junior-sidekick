package com.github.uncomplexco.sidekick.application.agent.workspace

import com.github.uncomplexco.sidekick.adapters.git.gitRepositoryCheckoutPath
import com.github.uncomplexco.sidekick.adapters.git.syncGitRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class GlobalWorkspaceConfig(
    val knowledge: List<GlobalWorkspaceRepository> = emptyList(),
)

@Serializable
data class GlobalWorkspaceRepository(
    val url: String,
    val path: String = "",
    val sshKeyPath: String? = null,
)

@Component
class LoadGlobalWorkspaceOnStartup(
    private val config: AgentConfig,
    private val globalWorkspace: GlobalWorkspace,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        globalWorkspace.sync(config)
    }
}

@Component
class GlobalWorkspace {
    private val json = Json

    fun sync(config: AgentConfig): List<Path> {
        val workspaceConfig = loadConfig(config)
        if (workspaceConfig.knowledge.isEmpty()) {
            return emptyList()
        }

        log.info(
            "Configured knowledge repositories: {}",
            workspaceConfig.knowledge.joinToString { "${it.url}:${it.path}" },
        )

        return workspaceConfig.knowledge.map { repository ->
            val checkout = checkoutPath(config, repository)
            log.info("Syncing knowledge repository {} into {}", repository.url, checkout)
            syncRepository(repository, checkout, config.workingDirectoryPath())
            checkout
        }
    }

    fun loadConfig(config: AgentConfig): GlobalWorkspaceConfig {
        val configFile = config.workspaceLayout().knowledgeConfigPath()
        if (!Files.exists(configFile)) {
            return GlobalWorkspaceConfig()
        }

        return json.decodeFromString<GlobalWorkspaceConfig>(Files.readString(configFile))
    }

    fun checkoutPath(
        config: AgentConfig,
        repository: GlobalWorkspaceRepository,
    ): Path = gitRepositoryCheckoutPath(config.workspaceLayout().knowledgeRepositoryDirectoryPath(), repository.url)

    private fun syncRepository(
        repository: GlobalWorkspaceRepository,
        checkout: Path,
        workingDirectory: Path,
    ) = syncGitRepository(repository.url, repository.sshKeyPath, checkout, workingDirectory, "Knowledge repository")

    private companion object {
        private val log = LoggerFactory.getLogger(GlobalWorkspace::class.java)
    }
}
