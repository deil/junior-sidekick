package com.github.uncomplexco.sidekick.application.agent.skills

import com.github.uncomplexco.sidekick.adapters.git.gitRepositoryCheckoutPath
import com.github.uncomplexco.sidekick.adapters.git.syncGitRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.application.utils.hasMarkdownFrontmatter
import com.github.uncomplexco.sidekick.application.utils.parseMarkdownFrontmatter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Serializable data class ExtensionsConfig(val extensions: List<ExtensionRepository> = emptyList())

@Serializable
data class ExtensionRepository(
    val url: String,
    val path: String = "",
    val sshKeyPath: String? = null,
)

data class SkillCatalog(val skills: List<Skill>) {
    fun matchingTags(tags: Set<String>): SkillCatalog =
        SkillCatalog(skills.filter { tags.containsAll(it.tags) })
}

data class Skill(
    val name: String,
    val description: String,
    val folder: Path,
    val disableModelInvocation: Boolean,
    val userInvocable: Boolean,
    val tags: Set<String> = emptySet(),
)

@Component
class LoadSkillsOnStartup(private val skills: SkillsCatalog) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        skills.reload()
    }
}

@Component
class Skills(private val config: AgentConfig) : SkillsCatalog {
    private val json = Json
    private var catalog: SkillCatalog = SkillCatalog(emptyList())

    override fun reload(): SkillCatalog {
        val extensionsConfig = loadConfig()
        if (extensionsConfig.extensions.isEmpty()) {
            catalog = SkillCatalog(emptyList())
            return catalog
        }

        Loggers.EXTENSIONS.info(
            "Configured extension repositories: {}",
            extensionsConfig.extensions.joinToString { "${it.url}:${it.path}" },
        )

        val checkouts =
            extensionsConfig.extensions.map { repository ->
                val checkout = checkoutPath(repository)
                Loggers.EXTENSIONS.info(
                    "Syncing extension repository {} into {}",
                    repository.url,
                    checkout,
                )
                syncRepository(repository, checkout, config.workingDirectoryPath())
                repository to checkout
            }

        catalog =
            checkouts
                .map { (repository, checkout) -> scanRepository(repository, checkout) }
                .fold(SkillCatalog(emptyList())) { acc, next ->
                    SkillCatalog(skills = acc.skills + next.skills)
                }

        Loggers.EXTENSIONS.info(
            "Identified skills: {}",
            formatSkills(catalog.skills, config.workingDirectoryPath()),
        )

        return catalog
    }

    override fun catalog(): SkillCatalog = catalog

    private fun loadConfig(): ExtensionsConfig {
        val configFile = config.workspaceLayout().extensionsConfigPath()
        if (!Files.exists(configFile)) {
            return ExtensionsConfig()
        }

        return json.decodeFromString<ExtensionsConfig>(Files.readString(configFile))
    }

    private fun checkoutPath(repository: ExtensionRepository): Path =
        gitRepositoryCheckoutPath(
            config.workspaceLayout().extensionsRepositoryDirectoryPath(),
            repository.url,
        )

    private fun scanRepository(
        repository: ExtensionRepository,
        checkout: Path,
    ): SkillCatalog {
        val skillsPath = checkout.resolve(repository.path.ifBlank { "." }).normalize()
        if (!Files.isDirectory(skillsPath)) {
            Loggers.EXTENSIONS.warn(
                "Skipping skill repository path {}: configured skills path does not exist",
                skillsPath,
            )
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
                        Loggers.EXTENSIONS.warn(
                            "Skipping skill folder {}: missing {}",
                            skillFolder,
                            SKILL_FILE_NAME,
                        )
                        return@forEach
                    }

                    try {
                        skills += parseSkill(skillFile)
                    } catch (ex: IllegalArgumentException) {
                        Loggers.EXTENSIONS.warn(
                            "Skipping skill folder {}: {}",
                            skillFolder,
                            ex.message ?: "invalid $SKILL_FILE_NAME",
                        )
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
    ) =
        syncGitRepository(
            repository.url,
            repository.sshKeyPath,
            checkout,
            workingDirectory,
            "Extension repository",
        )

    private fun parseSkill(skillFile: Path): Skill {
        val markdown = Files.readString(skillFile)
        require(hasMarkdownFrontmatter(markdown)) {
            "$SKILL_FILE_NAME must start with YAML frontmatter"
        }
        val frontmatter = parseMarkdownFrontmatter(markdown).frontmatter

        val name = (frontmatter["name"] as? String)?.takeUnless { it.isBlank() }
        val description =
            (frontmatter["description"] as? String)
                ?.takeUnless { it.isBlank() }
                ?.take(MAX_DESCRIPTION_LENGTH)
        val disableModelInvocation =
            frontmatter["disable-model-invocation"]?.toString()?.toBooleanStrictOrNull() ?: false
        val userInvocable =
            frontmatter["user-invocable"]?.toString()?.toBooleanStrictOrNull() ?: false
        val tags = frontmatter["tags"] ?: emptyList<String>()
        require(tags is List<*> && tags.all { it is String }) {
            "$SKILL_FILE_NAME tags must be a list of strings"
        }
        require(name != null) { "$SKILL_FILE_NAME is missing frontmatter field 'name'" }
        require(description != null) {
            "$SKILL_FILE_NAME is missing frontmatter field 'description'"
        }
        require(SKILL_NAME_RE.matches(name)) {
            "$SKILL_FILE_NAME frontmatter field 'name' must use lowercase letters, numbers, and single hyphens only"
        }
        require(name == skillFile.parent.name) {
            "$SKILL_FILE_NAME frontmatter field 'name' must match parent directory name"
        }

        return Skill(
            name,
            description,
            skillFile.parent,
            disableModelInvocation,
            userInvocable,
            tags.map { it as String }.toSet(),
        )
    }

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_DESCRIPTION_LENGTH = 1536
        val SKILL_NAME_RE = Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-)){0,62}[a-z0-9])?$")
    }
}
