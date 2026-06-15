package com.github.uncomplexco.sidekick.application.agent.workspace.global

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.adapters.git.gitRepositoryCheckoutPath
import com.github.uncomplexco.sidekick.adapters.git.syncGitRepository
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
    val global: List<GlobalWorkspaceRepository> = emptyList(),
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
        globalWorkspace.sync(config.workingDirectoryPath())
    }
}

@Component
class GlobalWorkspace {
    private val json = Json { ignoreUnknownKeys = true }

    fun sync(workingDirectory: Path): List<Path> {
        val config = loadConfig(workingDirectory)
        if (config.global.isEmpty()) {
            return emptyList()
        }

        log.info(
            "Configured global workspace repositories: {}",
            config.global.joinToString { "${it.url}:${it.path}" },
        )

        return config.global.map { repository ->
            val checkout = checkoutPath(workingDirectory, repository)
            log.info("Syncing global workspace repository {} into {}", repository.url, checkout)
            syncRepository(repository, checkout, workingDirectory)
            checkout
        }
    }

    fun loadConfig(workingDirectory: Path): GlobalWorkspaceConfig {
        val configFile = workingDirectory.resolve(GLOBAL_CONFIG_FILE)
        if (!Files.exists(configFile)) {
            return GlobalWorkspaceConfig()
        }

        return json.decodeFromString<GlobalWorkspaceConfig>(Files.readString(configFile))
    }

    fun checkoutPath(
        workingDirectory: Path,
        repository: GlobalWorkspaceRepository,
    ): Path = gitRepositoryCheckoutPath(workingDirectory.resolve(GLOBAL_CHECKOUT_DIRECTORY), repository.url)

    private fun syncRepository(
        repository: GlobalWorkspaceRepository,
        checkout: Path,
        workingDirectory: Path,
    ) = syncGitRepository(repository.url, repository.sshKeyPath, checkout, workingDirectory, "Global workspace repository")

    private companion object {
        private val log = LoggerFactory.getLogger(GlobalWorkspace::class.java)
        private const val GLOBAL_CONFIG_FILE = "global.json"
        private const val GLOBAL_CHECKOUT_DIRECTORY = "global"
    }
}
