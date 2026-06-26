package com.github.uncomplexco.sidekick.application.tools.skills

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloader
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloadResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillToolsTest {
    @TempDir
    lateinit var dir: Path

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
            """.trimIndent(),
        )
        Files.writeString(skillFolder.resolve("scripts/extract.py"), "print('extract')\n")

        // Act
        val result =
            SkillTools(
                skills =
                    {
                        SkillCatalog(
                            listOf(
                                Skill(
                                    name = "pdf-processing",
                                    description = "Work with PDFs.",
                                    folder = skillFolder,
                                    disableModelInvocation = false,
                                    userInvocable = true,
                                ),
                            ),
                        )
                    },
                virtualPaths = virtualPaths(),
                skillCatalogReloader = emptyReloader(),
            ).activateSkill("pdf-processing")

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
        val tools = SkillTools(skills = { SkillCatalog(emptyList()) }, virtualPaths = virtualPaths(), skillCatalogReloader = emptyReloader())

        // Act / Assert
        assertThrows<ToolException.ValidationFailure> {
            tools.activateSkill("missing")
        }
    }

    @Test
    fun `reload skills returns catalog summary`() {
        // Arrange
        val tools =
            SkillTools(
                skills = { SkillCatalog(emptyList()) },
                virtualPaths = virtualPaths(),
                skillCatalogReloader =
                    {
                        SkillCatalogReloadResult(
                            totalSkills = 3,
                            modelInvocableSkills = 2,
                            userInvocableSkills = 1,
                            skillNames = listOf("alpha", "beta", "gamma"),
                        )
                    },
            )

        // Act
        val result = tools.reloadSkills()

        // Assert
        assertContains(result.skill_names, "alpha")
        assertContains(result.skill_names, "beta")
        assertContains(result.skill_names, "gamma")
        assertEquals(true, result.ok)
        assertEquals(3, result.total_skills)
        assertEquals(2, result.model_invocable_skills)
        assertEquals(1, result.user_invocable_skills)
    }

    private fun emptyReloader(): SkillCatalogReloader =
        SkillCatalogReloader {
            SkillCatalogReloadResult(
                totalSkills = 0,
                modelInvocableSkills = 0,
                userInvocableSkills = 0,
                skillNames = emptyList(),
            )
        }

    private fun virtualPaths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
        )
}
