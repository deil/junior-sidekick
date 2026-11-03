package com.github.uncomplexco.sidekick.application.tools.skills

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.testing.StubSkillsCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SkillToolsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `activates skill with structured wrapping`() {
        // Arrange
        val skillFolder = dir.resolve("skills/repo/pdf-processing")
        Files.createDirectories(skillFolder.resolve("scripts"))
        Files.writeString(
            skillFolder.resolve("SKILL.md"),
            """
            ---
            name: pdf-processing
            description: Work with PDFs.
            ---
            # PDF Processing

            Use this skill for PDF work.
            """
                .trimIndent(),
        )
        Files.writeString(skillFolder.resolve("scripts/extract.py"), "print('extract')\n")

        // Act
        val result =
            SkillTools(
                    skills =
                        StubSkillsCatalog(
                            SkillCatalog(
                                listOf(
                                    Skill(
                                        name = "pdf-processing",
                                        description = "Work with PDFs.",
                                        folder = skillFolder,
                                        disableModelInvocation = false,
                                        userInvocable = true,
                                    )
                                )
                            )
                        ),
                    virtualPaths = virtualPaths(),
                )
                .activateSkill("pdf-processing")

        // Assert
        assertContains(result, "<skill_content name=\"pdf-processing\">")
        assertContains(result, "# PDF Processing")
        assertContains(result, "Use this skill for PDF work.")
        assertContains(result, "Skill directory: /data/skills/repo/pdf-processing")
        assertContains(result, "Relative paths in this skill are relative to the skill directory.")
        assertContains(result, "<skill_resources>")
        assertContains(result, "<file>scripts/extract.py</file>")
        assertFalse(result.contains("description: Work with PDFs."), result)
    }

    @Test
    fun `rejects unknown skill`() {
        // Arrange
        val tools =
            SkillTools(
                skills = StubSkillsCatalog(),
                virtualPaths = virtualPaths(),
            )

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> { tools.activateSkill("missing") }
    }

    @Test
    fun `reload skills returns catalog summary`() {
        // Arrange
        val tools =
            SkillsManagementTools(
                skills =
                    StubSkillsCatalog(
                        reloaded =
                            SkillCatalog(
                                listOf(
                                    Skill("gamma", "Gamma", dir, true, false),
                                    Skill("alpha", "Alpha", dir, false, true),
                                    Skill("beta", "Beta", dir, false, false),
                                )
                            )
                    )
            )

        // Act
        val result = tools.reloadSkills()

        // Assert
        assertEquals(listOf("alpha", "beta", "gamma"), result.skillNames)
        assertEquals(true, result.ok)
        assertEquals(3, result.totalSkills)
        assertEquals(2, result.modelInvocableSkills)
        assertEquals(1, result.userInvocableSkills)
    }

    @Test
    fun `reload result preserves snake case response fields`() {
        // Arrange
        val result = ReloadSkillsResult(true, 3, 2, 1, listOf("alpha", "beta", "gamma"))

        // Act
        val response = Json.encodeToJsonElement(ReloadSkillsResult.serializer(), result)

        // Assert
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                    "ok": true,
                    "total_skills": 3,
                    "model_invocable_skills": 2,
                    "user_invocable_skills": 1,
                    "skill_names": ["alpha", "beta", "gamma"]
                }
                """
                    .trimIndent()
            ),
            response,
        )
    }

    private fun virtualPaths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = dir.resolve("project"),
        )
}
