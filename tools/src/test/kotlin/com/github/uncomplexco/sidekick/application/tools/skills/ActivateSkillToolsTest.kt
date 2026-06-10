package com.github.uncomplexco.sidekick.application.tools.skills

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ActivateSkillToolsTest {
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
            ActivateSkillTools(
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
                skillsRoot = dir.resolve("skills"),
            ).activateSkill("pdf-processing")

        // Assert
        assertContains(result, "<skill_content name=\"pdf-processing\">")
        assertContains(result, "# PDF Processing")
        assertContains(result, "Use this skill for PDF work.")
        assertContains(result, "Skill directory: skills:/repo/pdf-processing")
        assertContains(result, "Relative paths in this skill are relative to the skill directory.")
        assertContains(result, "<skill_resources>")
        assertContains(result, "<file>scripts/extract.py</file>")
        assertFalse(result.contains("description: Work with PDFs."), result)
    }

    @Test
    fun `rejects unknown skill`() {
        val tools = ActivateSkillTools(skills = { SkillCatalog(emptyList()) }, skillsRoot = dir.resolve("skills"))

        assertThrows<ToolException.ValidationFailure> {
            tools.activateSkill("missing")
        }
    }
}
