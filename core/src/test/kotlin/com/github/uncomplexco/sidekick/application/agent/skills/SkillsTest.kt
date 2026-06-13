package com.github.uncomplexco.sidekick.application.agent.skills

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillsTest {
    @TempDir
    lateinit var dir: Path

    private val skills = Skills()

    @Test
    fun `loads skills repository config from working directory`() {
        // Arrange
        Files.writeString(
            dir.resolve("skills.json"),
            """
            {"skills": [{"url": "git@github.com:deil/skills.git", "path": "skills", "sshKeyPath": "/home/sidekick/.ssh/skills"}]}
            """.trimIndent(),
        )

        // Act
        val config = skills.loadConfig(dir)

        // Assert
        assertEquals(listOf(SkillsRepository("git@github.com:deil/skills.git", "skills", "/home/sidekick/.ssh/skills")), config.skills)
    }

    @Test
    fun `defaults missing repository path to repository root`() {
        // Arrange
        Files.writeString(
            dir.resolve("skills.json"),
            """
            {"skills": [{"url": "git@github.com:deil/skills.git"}]}
            """.trimIndent(),
        )

        // Act
        val config = skills.loadConfig(dir)

        // Assert
        assertEquals(listOf(SkillsRepository(url = "git@github.com:deil/skills.git")), config.skills)
    }

    @Test
    fun `returns empty config when skills config file is missing`() {
        // Act
        val config = skills.loadConfig(dir)

        // Assert
        assertEquals(emptyList(), config.skills)
    }

    @Test
    fun `does nothing when skills config file is missing`() {
        // Act
        val catalog = skills.syncAndScan(dir)

        // Assert
        assertEquals(emptyList(), catalog.skills)
        assertTrue(Files.notExists(dir.resolve("skills")))
    }

    @Test
    fun `does nothing when skills config is empty`() {
        // Arrange
        Files.writeString(dir.resolve("skills.json"), """{"skills": []}""")

        // Act
        val catalog = skills.syncAndScan(dir)

        // Assert
        assertEquals(emptyList(), catalog.skills)
        assertTrue(Files.notExists(dir.resolve("skills")))
    }

    @Test
    fun `checkout path is stable and unique per repository url`() {
        // Arrange
        val repo = SkillsRepository("git@github.com:deil/skills.git", "skills")

        // Act
        val first = skills.checkoutPath(dir, repo)
        val second = skills.checkoutPath(dir, repo)
        val other = skills.checkoutPath(dir, SkillsRepository("git@github.com:deil/other-skills.git", "skills"))

        // Assert
        assertEquals(first, second)
        assertTrue(first.startsWith(dir.resolve("skills")))
        assertTrue(first.fileName.toString().startsWith("skills-"))
        assertTrue(other.fileName.toString().startsWith("other-skills-"))
        assertTrue(first != other)
    }

    @Test
    fun `syncs and scans local git repository`() {
        // Arrange
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkillFile(source.resolve("skills/local-skill/SKILL.md"), "local-skill", "Local skill.")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add skill").call()
        }
        Files.writeString(
            dir.resolve("skills.json"),
            """
            {"skills": [{"url": "${source.toUri()}", "path": "skills"}]}
            """.trimIndent(),
        )

        // Act
        val catalog = skills.syncAndScan(dir)

        // Assert
        assertEquals(listOf("local-skill"), catalog.skills.map { it.name })
    }

    @Test
    fun `scans valid skills and skips bad skill folders from configured repository path`() {
        // Arrange
        val repo = SkillsRepository("git@github.com:deil/skills.git", "skills")
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

        // Act
        val catalog = skills.scanRepository(repo, checkout)

        // Assert
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
        // Arrange
        val repo = SkillsRepository("git@github.com:deil/skills.git", path = "")
        val checkout = Files.createDirectories(dir.resolve("checkout"))
        writeSkillFile(checkout.resolve("root-skill/SKILL.md"), "root-skill", "Root skill.")

        // Act
        val catalog = skills.scanRepository(repo, checkout)

        // Assert
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

}
