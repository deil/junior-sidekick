package com.github.uncomplexco.sidekick.application.agent.skills

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SkillsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `missing configuration produces an empty catalog without checkouts`() {
        val skills: SkillsCatalog = Skills(config())

        val catalog = skills.reload()

        assertEquals(emptyList(), catalog.skills)
        assertSame(catalog, skills.catalog())
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/extensions")))
    }

    @Test
    fun `empty configuration produces an empty catalog without checkouts`() {
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """{"extensions": []}""",
        )
        val skills: SkillsCatalog = Skills(config())

        val catalog = skills.reload()

        assertEquals(emptyList(), catalog.skills)
        assertSame(catalog, skills.catalog())
        assertTrue(Files.notExists(dir.resolve("workspace/data/repositories/extensions")))
    }

    @Test
    fun `reload discovers valid skills and skips invalid folders at configured path`() {
        // Arrange
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkill(source.resolve("skills/valid"), "valid", "Valid skill.", true, false)
        writeSkill(source.resolve("skills/default-flag"), "default-flag", "Default flags.")
        writeSkill(
            source.resolve("skills/too-long-description"),
            "too-long-description",
            "x".repeat(1537),
        )
        writeSkill(source.resolve("skills/wrong-name"), "other-name", "Invalid name.")
        Files.createDirectories(source.resolve("skills/missing-file"))
        Files.writeString(source.resolve("skills/missing-file/README.md"), "No skill instructions")
        val missingDescription =
            Files.createDirectories(source.resolve("skills/missing-description"))
        Files.writeString(
            missingDescription.resolve("SKILL.md"),
            "---\nname: missing-description\n---\nInstructions",
        )
        initializeRepository(source)
        configure(source, "skills")
        val skills: SkillsCatalog = Skills(config())

        // Act
        val catalog = skills.reload()

        // Assert
        assertEquals(
            listOf("default-flag", "too-long-description", "valid"),
            catalog.skills.map { it.name },
        )
        val valid = catalog.skills.single { it.name == "valid" }
        assertEquals(true, valid.disableModelInvocation)
        assertEquals(false, valid.userInvocable)
        assertTrue(valid.folder.startsWith(dir.resolve("workspace/data/repositories/extensions")))
        assertTrue(Files.isRegularFile(valid.folder.resolve("SKILL.md")))
        val defaultFlags = catalog.skills.single { it.name == "default-flag" }
        assertEquals(false, defaultFlags.disableModelInvocation)
        assertEquals(false, defaultFlags.userInvocable)
        assertEquals(
            1536,
            catalog.skills.single { it.name == "too-long-description" }.description.length,
        )
        assertSame(catalog, skills.catalog())
    }

    @Test
    fun `omitted repository path discovers skills at repository root`() {
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkill(source.resolve("root-skill"), "root-skill", "Root skill.")
        initializeRepository(source)
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """{"extensions": [{"url": "${source.toUri()}"}]}""",
        )
        val skills: SkillsCatalog = Skills(config())

        val catalog = skills.reload()

        assertEquals(listOf("root-skill"), catalog.skills.map { it.name })
    }

    @Test
    fun `reload refreshes added updated and removed skills from source`() {
        // Arrange
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkill(source.resolve("skills/existing"), "existing", "Old instructions.")
        writeSkill(source.resolve("skills/removed"), "removed", "Remove me.")
        initializeRepository(source)
        configure(source, "skills")
        val skills: SkillsCatalog = Skills(config())
        skills.reload()
        writeSkill(source.resolve("skills/existing"), "existing", "Updated instructions.")
        writeSkill(source.resolve("skills/added"), "added", "New skill.")
        Git.open(source.toFile()).use { git ->
            git.rm().addFilepattern("skills/removed/SKILL.md").call()
            git.add().addFilepattern(".").call()
            git.commit().setMessage("update skills").call()
        }

        // Act
        val catalog = skills.reload()

        // Assert
        assertEquals(listOf("added", "existing"), catalog.skills.map { it.name })
        assertEquals(
            "Updated instructions.",
            catalog.skills.single { it.name == "existing" }.description,
        )
        assertSame(catalog, skills.catalog())
    }

    @Test
    fun `reload reads changed source configuration`() {
        val first = Files.createDirectories(dir.resolve("first"))
        writeSkill(first.resolve("skills/first"), "first", "First source.")
        initializeRepository(first)
        val second = Files.createDirectories(dir.resolve("second"))
        writeSkill(second.resolve("skills/second"), "second", "Second source.")
        initializeRepository(second)
        configure(first, "skills")
        val skills: SkillsCatalog = Skills(config())
        skills.reload()
        configure(second, "skills")

        val catalog = skills.reload()

        assertEquals(listOf("second"), catalog.skills.map { it.name })
        assertSame(catalog, skills.catalog())
    }

    @Test
    fun `removing configured sources clears the current catalog`() {
        val source = Files.createDirectories(dir.resolve("source"))
        writeSkill(source.resolve("skills/old"), "old", "Old source.")
        initializeRepository(source)
        configure(source, "skills")
        val skills: SkillsCatalog = Skills(config())
        skills.reload()
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """{"extensions": []}""",
        )

        val catalog = skills.reload()

        assertEquals(emptyList(), catalog.skills)
        assertSame(catalog, skills.catalog())
    }

    private fun initializeRepository(source: Path) {
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add skills").call()
        }
    }

    private fun configure(source: Path, path: String) {
        Files.writeString(
            config().workspaceLayout().extensionsConfigPath(),
            """{"extensions": [{"url": "${source.toUri()}", "path": "$path"}]}""",
        )
    }

    private fun writeSkill(
        folder: Path,
        name: String,
        description: String,
        disableModelInvocation: Boolean = false,
        userInvocable: Boolean = false,
    ) {
        Files.createDirectories(folder)
        Files.writeString(
            folder.resolve("SKILL.md"),
            """
            ---
            name: $name
            description: $description
            disable-model-invocation: $disableModelInvocation
            user-invocable: $userInvocable
            ---
            # Instructions
            """
                .trimIndent(),
        )
    }

    private fun config(): AgentConfig =
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )
}
