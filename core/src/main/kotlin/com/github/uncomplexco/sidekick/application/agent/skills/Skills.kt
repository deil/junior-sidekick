package com.github.uncomplexco.sidekick.application.agent.skills

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Serializable
data class SkillsConfig(
    val skills: List<SkillsRepository> = emptyList(),
)

@Serializable
data class SkillsRepository(
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
        skills.syncAndScan(config.workingDirectoryPath())
    }
}

@Component
class Skills : SkillCatalogProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private var catalog: SkillCatalog = SkillCatalog(emptyList())

    fun syncAndScan(workingDirectory: Path): SkillCatalog {
        val config = loadConfig(workingDirectory)
        if (config.skills.isEmpty()) {
            catalog = SkillCatalog(emptyList())
            return catalog
        }

        log.info(
            "Configured remote skill repositories: {}",
            config.skills.joinToString { "${it.url}:${it.path}" },
        )

        val checkouts =
            config.skills.map { repository ->
                val checkout = checkoutPath(workingDirectory, repository)
                log.info("Syncing skill repository {} into {}", repository.url, checkout)
                syncRepository(repository, checkout, workingDirectory)
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
            formatSkills(catalog.skills, workingDirectory),
        )

        return catalog
    }

    override fun catalog(): SkillCatalog = catalog

    fun loadConfig(workingDirectory: Path): SkillsConfig {
        val configFile = workingDirectory.resolve(SKILLS_CONFIG_FILE)
        if (!Files.exists(configFile)) {
            return SkillsConfig()
        }

        return json.decodeFromString<SkillsConfig>(Files.readString(configFile))
    }

    fun checkoutPath(
        workingDirectory: Path,
        repository: SkillsRepository,
    ): Path {
        val repositoryName =
            repository.url
                .substringAfterLast('/')
                .substringAfterLast(':')
                .removeSuffix(".git")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "repository" }
        return workingDirectory
            .resolve(SKILLS_CHECKOUT_DIRECTORY)
            .resolve("$repositoryName-${sha256(repository.url).take(12)}")
    }

    fun scanRepository(
        repository: SkillsRepository,
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
        repository: SkillsRepository,
        checkout: Path,
        workingDirectory: Path,
    ) {
        Files.createDirectories(checkout.parent)
        if (!Files.exists(checkout)) {
            Git
                .cloneRepository()
                .setURI(repository.url)
                .setDirectory(checkout.toFile())
                .applySsh(repository, workingDirectory)
                .call()
                .close()
            return
        }

        require(Files.isDirectory(checkout.resolve(".git"))) {
            "Skill repository checkout exists but is not a Git repository: $checkout"
        }

        Git.open(checkout.toFile()).use { git ->
            git
                .fetch()
                .setRemote("origin")
                .setRemoveDeletedRefs(true)
                .applySsh(repository, workingDirectory)
                .call()
            git
                .reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef(defaultRemoteBranch(git))
                .call()
        }
    }

    private fun defaultRemoteBranch(git: Git): String =
        Constants.R_REMOTES + "origin/" + git.repository.branchName()

    private fun Repository.branchName(): String = branch

    private fun org.eclipse.jgit.api.CloneCommand.applySsh(
        repository: SkillsRepository,
        workingDirectory: Path,
    ) =
        apply {
            repository.sshKeyPath?.let { setTransportConfigCallback(sshKeyCallback(it, workingDirectory)) }
        }

    private fun org.eclipse.jgit.api.FetchCommand.applySsh(
        repository: SkillsRepository,
        workingDirectory: Path,
    ) =
        apply {
            repository.sshKeyPath?.let { setTransportConfigCallback(sshKeyCallback(it, workingDirectory)) }
        }

    private fun sshKeyCallback(
        sshKeyPath: String,
        workingDirectory: Path,
    ) =
        SshKeyTransportConfigCallback(
            sshKeyPath = Path.of(sshKeyPath).toAbsolutePath().normalize(),
            sshHomeDirectory = workingDirectory.resolve("tmp").toAbsolutePath().normalize(),
        )

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
        val userInvocable = frontmatter["user-invocable"]?.toBooleanStrictOrNull() ?: true
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
        private const val SKILLS_CONFIG_FILE = "skills.json"
        private const val SKILLS_CHECKOUT_DIRECTORY = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        private const val FRONT_MATTER_DELIMITER = "---"
        private const val MAX_DESCRIPTION_LENGTH = 1536
        private val SKILL_NAME_RE = Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-)){0,62}[a-z0-9])?$")
    }
}

private class SshKeyTransportConfigCallback(
    private val sshKeyPath: Path,
    private val sshHomeDirectory: Path,
) : TransportConfigCallback {
    override fun configure(transport: Transport) {
        Files.createDirectories(sshHomeDirectory)
        (transport as SshTransport).sshSessionFactory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(sshHomeDirectory.toFile())
                .setSshDirectory(sshHomeDirectory.toFile())
                .setDefaultIdentities { listOf(sshKeyPath) }
                .setServerKeyDatabase { _, _ -> TrustingServerKeyDatabase }
                .build(null) as SshdSessionFactory
    }
}

private object TrustingServerKeyDatabase : ServerKeyDatabase {
    override fun lookup(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        config: ServerKeyDatabase.Configuration,
    ): List<PublicKey> = emptyList()

    override fun accept(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        serverKey: PublicKey,
        config: ServerKeyDatabase.Configuration,
        provider: CredentialsProvider?,
    ): Boolean = true
}
