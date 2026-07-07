package com.github.uncomplexco.sidekick.application.agent.skills

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
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Serializable
data class ExtensionsConfig(
    val extensions: List<ExtensionRepository> = emptyList(),
)

@Serializable
data class ExtensionRepository(
    val url: String,
    val path: String = "",
    val sshKeyPath: String? = null,
)

data class SkillCatalog(
    val skills: List<Skill>,
)

data class Skill(
    val name: String,
    val description: String,
    val folder: Path,
    val disableModelInvocation: Boolean,
    val userInvocable: Boolean,
)

fun interface SkillCatalogProvider {
    fun catalog(): SkillCatalog
}

@Component
class LoadSkillsOnStartup(
    private val config: AgentConfig,
    private val skills: Skills,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        skills.syncAndScan(config)
    }
}

@Component
class Skills : SkillCatalogProvider {
    private val json = Json
    private var catalog: SkillCatalog = SkillCatalog(emptyList())

    fun syncAndScan(config: AgentConfig): SkillCatalog {
        val extensionsConfig = loadConfig(config)
        if (extensionsConfig.extensions.isEmpty()) {
            catalog = SkillCatalog(emptyList())
            return catalog
        }

        log.info(
            "Configured extension repositories: {}",
            extensionsConfig.extensions.joinToString { "${it.url}:${it.path}" },
        )

        val checkouts =
            extensionsConfig.extensions.map { repository ->
                val checkout = checkoutPath(config, repository)
                log.info("Syncing extension repository {} into {}", repository.url, checkout)
                syncRepository(repository, checkout, config.workingDirectoryPath())
                repository to checkout
            }

        catalog =
            checkouts
                .map { (repository, checkout) -> scanRepository(repository, checkout) }
                .fold(SkillCatalog(emptyList())) { acc, next ->
                    SkillCatalog(
                        skills = acc.skills + next.skills,
                    )
                }

        log.info(
            "Identified skills: {}",
            formatSkills(catalog.skills, config.workingDirectoryPath()),
        )

        return catalog
    }

    override fun catalog(): SkillCatalog = catalog

    fun loadConfig(config: AgentConfig): ExtensionsConfig {
        val configFile = config.workspaceLayout().extensionsConfigPath()
        if (!Files.exists(configFile)) {
            return ExtensionsConfig()
        }

        return json.decodeFromString<ExtensionsConfig>(Files.readString(configFile))
    }

    fun checkoutPath(
        config: AgentConfig,
        repository: ExtensionRepository,
    ): Path = gitRepositoryCheckoutPath(config.workspaceLayout().extensionsRepositoryDirectoryPath(), repository.url)

    fun scanRepository(
        repository: ExtensionRepository,
        checkout: Path,
    ): SkillCatalog {
        val skillsPath = checkout.resolve(repository.path.ifBlank { "." }).normalize()
        if (!Files.isDirectory(skillsPath)) {
            log.warn("Skipping skill repository path {}: configured skills path does not exist", skillsPath)
            return SkillCatalog(emptyList())
        }

        val skills = mutableListOf<Skill>()
        Files.list(skillsPath).use { paths ->
            paths
                .filter { it.isDirectory() }
                .sorted(Comparator.comparing { it.name })
                .forEach { skillFolder ->
                    val skillFile = skillFolder.resolve(SKILL_FILE_NAME)
                    if (!Files.isRegularFile(skillFile)) {
                        log.warn("Skipping skill folder {}: missing {}", skillFolder, SKILL_FILE_NAME)
                        return@forEach
                    }

                    try {
                        skills += parseSkill(skillFile)
                    } catch (ex: IllegalArgumentException) {
                        log.warn("Skipping skill folder {}: {}", skillFolder, ex.message ?: "invalid $SKILL_FILE_NAME")
                    }
                }
        }

        return SkillCatalog(skills)
    }

    private fun formatSkills(
        skills: List<Skill>,
        workingDirectory: Path,
    ): String {
        if (skills.isEmpty()) {
            return "(none)"
        }

        val root = workingDirectory.toAbsolutePath().normalize()
        return skills.joinToString { skill ->
            val relativePath = root.relativize(skill.folder.toAbsolutePath().normalize())
            "${skill.name} ($relativePath)"
        }
    }

    private fun syncRepository(
        repository: ExtensionRepository,
        checkout: Path,
        workingDirectory: Path,
    ) = syncGitRepository(repository.url, repository.sshKeyPath, checkout, workingDirectory, "Extension repository")

    private fun parseSkill(skillFile: Path): Skill {
        val lines = Files.readString(skillFile).lines()
        require(lines.firstOrNull()?.trim() == FRONT_MATTER_DELIMITER) {
            "$SKILL_FILE_NAME must start with YAML frontmatter"
        }

        val closingDelimiterIndex = lines.drop(1).indexOfFirst { it.trim() == FRONT_MATTER_DELIMITER }
        require(closingDelimiterIndex >= 0) {
            "$SKILL_FILE_NAME must contain a closing YAML frontmatter delimiter"
        }

        val frontmatter =
            lines
                .subList(1, closingDelimiterIndex + 1)
                .mapNotNull { line ->
                    val separatorIndex = line.indexOf(':')
                    if (separatorIndex <= 0) {
                        null
                    } else {
                        line.substring(0, separatorIndex).trim() to cleanYamlScalar(line.substring(separatorIndex + 1))
                    }
                }.toMap()

        val name = frontmatter["name"]?.takeUnless { it.isBlank() }
        val description = frontmatter["description"]?.takeUnless { it.isBlank() }?.take(MAX_DESCRIPTION_LENGTH)
        val disableModelInvocation = frontmatter["disable-model-invocation"]?.toBooleanStrictOrNull() ?: false
        val userInvocable = frontmatter["user-invocable"]?.toBooleanStrictOrNull() ?: false
        require(name != null) { "$SKILL_FILE_NAME is missing frontmatter field 'name'" }
        require(description != null) { "$SKILL_FILE_NAME is missing frontmatter field 'description'" }
        require(SKILL_NAME_RE.matches(name)) {
            "$SKILL_FILE_NAME frontmatter field 'name' must use lowercase letters, numbers, and single hyphens only"
        }
        require(name == skillFile.parent.name) {
            "$SKILL_FILE_NAME frontmatter field 'name' must match parent directory name"
        }

        return Skill(name, description, skillFile.parent, disableModelInvocation, userInvocable)
    }

    companion object {
        private val log = LoggerFactory.getLogger(Skills::class.java)
        const val SKILL_FILE_NAME = "SKILL.md"
        private const val FRONT_MATTER_DELIMITER = "---"
        private const val MAX_DESCRIPTION_LENGTH = 1536
        private val SKILL_NAME_RE = Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-)){0,62}[a-z0-9])?$")
    }
}
