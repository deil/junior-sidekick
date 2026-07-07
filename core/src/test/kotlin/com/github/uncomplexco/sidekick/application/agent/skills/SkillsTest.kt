package com.github.uncomplexco.sidekick.application.agent.skills

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillsTest {
    @TempDir
    lateinit var dir: Path

    private val skills = Skills()

    @Test
    fun `loads extension repository config from workspace config directory`() {
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """
            {"extensions": [{"url": "git@github.com:deil/skills.git", "path": "skills", "sshKeyPath": "/home/sidekick/.ssh/skills"}]}
            """.trimIndent(),
        )

        val config = skills.loadConfig(config())

        assertEquals(listOf(ExtensionRepository("git@github.com:deil/skills.git", "skills", "/home/sidekick/.ssh/skills")), config.extensions)
    }

    @Test
    fun `defaults missing repository path to repository root`() {
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """
            {"extensions": [{"url": "git@github.com:deil/skills.git"}]}
            """.trimIndent(),
        )

        val config = skills.loadConfig(config())

        assertEquals(listOf(ExtensionRepository(url = "git@github.com:deil/skills.git")), config.extensions)
    }

    @Test
    fun `returns empty config when extensions config file is missing`() {
        val config = skills.loadConfig(config())

        assertEquals(emptyList(), config.extensions)
    }

    @Test
    fun `does nothing when extensions config file is missing`() {
        val catalog = skills.syncAndScan(config())

        assertEquals(emptyList(), catalog.skills)
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/extensions")))
    }

    @Test
    fun `does nothing when extensions config is empty`() {
        Files.writeString(config().workspaceLayout().extensionsConfigPath(), """{"extensions": []}""")

        val catalog = skills.syncAndScan(config())

        assertEquals(emptyList(), catalog.skills)
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/extensions")))
    }

    @Test
    fun `configured reloader syncs and summarizes catalog`() {
        Files.writeString(config().workspaceLayout().extensionsConfigPath(), """{"extensions": []}""")
        val reloader = ConfiguredSkillCatalogReloader(config(), skills)

        val result = reloader.reloadSkills()

        assertEquals(0, result.totalSkills)
        assertEquals(0, result.modelInvocableSkills)
        assertEquals(0, result.userInvocableSkills)
        assertEquals(emptyList(), result.skillNames)
    }

    @Test
    fun `checkout path is stable and unique per repository url`() {
        val repo = ExtensionRepository("git@github.com:deil/skills.git", "skills")

        val first = skills.checkoutPath(config(), repo)
        val second = skills.checkoutPath(config(), repo)
        val other = skills.checkoutPath(config(), ExtensionRepository("git@github.com:deil/other-skills.git", "skills"))

        assertEquals(first, second)
        assertTrue(first.startsWith(dir.resolve("workspace/data/repositories/extensions")))
        assertTrue(first.fileName.toString().startsWith("skills-"))
        assertTrue(other.fileName.toString().startsWith("other-skills-"))
        assertTrue(first != other)
    }

    @Test
    fun `syncs and scans local git repository`() {
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkillFile(source.resolve("skills/local-skill/SKILL.md"), "local-skill", "Local skill.")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add skill").call()
        }
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """
            {"extensions": [{"url": "${source.toUri()}", "path": "skills"}]}
            """.trimIndent(),
        )

        val catalog = skills.syncAndScan(config())

        assertEquals(listOf("local-skill"), catalog.skills.map { it.name })
    }

    @Test
    fun `scans valid skills and skips bad skill folders from configured repository path`() {
        val repo = ExtensionRepository("git@github.com:deil/skills.git", "skills")
        val checkout = Files.createDirectories(dir.resolve("checkout"))
        val skillsRoot = Files.createDirectories(checkout.resolve("skills"))
        Files.createDirectories(skillsRoot.resolve("valid"))
        Files.writeString(
            skillsRoot.resolve("valid/SKILL.md"),
            """
            ---
            name: valid
            description: Valid skill.
            disable-model-invocation: true
            user-invocable: false
            ---
            # Instructions
            """.trimIndent(),
        )
        Files.createDirectories(skillsRoot.resolve("default-flag"))
        Files.writeString(
            skillsRoot.resolve("default-flag/SKILL.md"),
            """
            ---
            name: default-flag
            description: Defaults optional flags.
            ---
            # Instructions
            """.trimIndent(),
        )
        Files.createDirectories(skillsRoot.resolve("max-description"))
        Files.writeString(
            skillsRoot.resolve("max-description/SKILL.md"),
            """
            ---
            name: max-description
            description: ${"x".repeat(1536)}
            ---
            # Instructions
            """.trimIndent(),
        )
        Files.createDirectories(skillsRoot.resolve("too-long-description"))
        Files.writeString(
            skillsRoot.resolve("too-long-description/SKILL.md"),
            """
            ---
            name: too-long-description
            description: ${"x".repeat(1537)}
            ---
            # Instructions
            """.trimIndent(),
        )
        Files.createDirectories(skillsRoot.resolve("missing-file"))
        Files.createDirectories(skillsRoot.resolve("missing-description"))
        Files.writeString(
            skillsRoot.resolve("missing-description/SKILL.md"),
            """
            ---
            name: missing-description
            ---
            # Instructions
            """.trimIndent(),
        )
        Files.createDirectories(skillsRoot.resolve("wrong-name"))
        Files.writeString(
            skillsRoot.resolve("wrong-name/SKILL.md"),
            """
            ---
            name: other-name
            description: Name does not match folder.
            ---
            # Instructions
            """.trimIndent(),
        )

        val catalog = skills.scanRepository(repo, checkout)

        assertEquals(listOf("default-flag", "max-description", "too-long-description", "valid"), catalog.skills.map { it.name })
        assertEquals(skillsRoot.resolve("valid"), catalog.skills.single { it.name == "valid" }.folder)
        assertEquals(true, catalog.skills.single { it.name == "valid" }.disableModelInvocation)
        assertEquals(false, catalog.skills.single { it.name == "valid" }.userInvocable)
        assertEquals(false, catalog.skills.single { it.name == "default-flag" }.disableModelInvocation)
        assertEquals(false, catalog.skills.single { it.name == "default-flag" }.userInvocable)
        assertEquals(1536, catalog.skills.single { it.name == "too-long-description" }.description.length)
    }

    @Test
    fun `scans repository root when configured path is blank`() {
        val repo = ExtensionRepository("git@github.com:deil/skills.git", path = "")
        val checkout = Files.createDirectories(dir.resolve("checkout"))
        writeSkillFile(checkout.resolve("root-skill/SKILL.md"), "root-skill", "Root skill.")

        val catalog = skills.scanRepository(repo, checkout)

        assertEquals(listOf("root-skill"), catalog.skills.map { it.name })
    }

    private fun writeSkillFile(
        skillFile: Path,
        name: String,
        description: String,
    ) {
        Files.createDirectories(skillFile.parent)
        Files.writeString(
            skillFile,
            """
            ---
            name: $name
            description: $description
            ---
            # Instructions
            """.trimIndent(),
        )
    }

    private fun config(): AgentConfig = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
}
