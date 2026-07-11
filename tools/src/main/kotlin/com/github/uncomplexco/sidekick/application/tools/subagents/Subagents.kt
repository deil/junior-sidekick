package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.adapters.git.gitRepositoryCheckoutPath
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.ExtensionRepository
import com.github.uncomplexco.sidekick.application.agent.skills.ExtensionsConfig
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.application.utils.parseMarkdownFrontmatter
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

@Component
class Subagents(
    private val config: AgentConfig? = null,
    private val classLoader: ClassLoader = Subagents::class.java.classLoader,
) : SubagentCatalogProvider {
    private val json = Json

    override fun catalog(): SubagentCatalog = SubagentCatalog(subagentsByName().values.toList())

    private fun subagentsByName(): Map<String, Subagent> =
        buildMap {
            BUILT_IN_SUBAGENTS.forEach { subagent -> put(subagent, loadBuiltIn(subagent)) }
            loadExtensionSubagents().forEach { subagent ->
                if (containsKey(subagent.name)) {
                    Loggers.EXTENSIONS.warn("Skipping extension subagent {}: a subagent with that name already exists", subagent.name)
                } else {
                    put(subagent.name, subagent)
                }
            }
        }

    private fun loadBuiltIn(name: String): Subagent {
        require(AGENT_NAME_RE.matches(name)) { "Unknown subagent type: $name" }

        val resource = classLoader.getResource("agents/$name.md") ?: throw IllegalArgumentException("Unknown subagent type: $name")
        return parseSubagent(name, resource.readText())
    }

    private fun loadExtensionSubagents(): List<Subagent> {
        val config = config ?: return emptyList()
        val configFile = config.workspaceLayout().extensionsConfigPath()
        if (!Files.exists(configFile)) {
            return emptyList()
        }

        return json
            .decodeFromString<ExtensionsConfig>(Files.readString(configFile))
            .extensions
            .flatMap { repository -> scanRepository(config, repository) }
    }

    private fun scanRepository(
        config: AgentConfig,
        repository: ExtensionRepository,
    ): List<Subagent> {
        val checkout = gitRepositoryCheckoutPath(config.workspaceLayout().extensionsRepositoryDirectoryPath(), repository.url)
        val subagentsPath = checkout.resolve(repository.path.ifBlank { "." }).normalize().resolve("agents")
        if (!Files.isDirectory(subagentsPath)) {
            return emptyList()
        }

        val subagents = mutableListOf<Subagent>()
        Files.list(subagentsPath).use { paths ->
            paths
                .filter { it.isRegularFile() && it.extension == "md" }
                .sorted(Comparator.comparing { it.name })
                .forEach { subagentFile ->
                    try {
                        subagents += parseSubagentFile(subagentFile)
                    } catch (ex: IllegalArgumentException) {
                        Loggers.EXTENSIONS.warn(
                            "Skipping subagent definition {}: {}",
                            subagentFile,
                            ex.message ?: "invalid subagent definition",
                        )
                    }
                }
        }
        return subagents
    }

    private fun parseSubagentFile(subagentFile: Path): Subagent {
        val subagent = parseSubagent(subagentFile.nameWithoutExtension, Files.readString(subagentFile))
        require(subagent.name == subagentFile.nameWithoutExtension) {
            "frontmatter field 'name' must match file name"
        }
        return subagent
    }

    private fun parseSubagent(
        expectedName: String,
        markdown: String,
    ): Subagent {
        val document = parseMarkdownFrontmatter(markdown)
        val name = document.frontmatter["name"]?.takeUnless { it.isBlank() } ?: expectedName
        val description = document.frontmatter["description"]?.takeUnless { it.isBlank() }
        require(description != null) { "frontmatter field 'description' is required" }
        require(AGENT_NAME_RE.matches(name)) {
            "frontmatter field 'name' must use lowercase letters, numbers, and single hyphens only"
        }
        return Subagent(
            name = name,
            description = description,
            systemPrompt = document.body,
        )
    }

    companion object {
        val BUILT_IN_SUBAGENTS = listOf("general")
        val AGENT_NAME_RE = Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-))*[a-z0-9])?$")
    }
}

data class Subagent(
    val name: String,
    val description: String,
    val systemPrompt: String,
)

data class SubagentCatalog(
    val subagents: List<Subagent>,
)

fun interface SubagentCatalogProvider {
    fun catalog(): SubagentCatalog
}
