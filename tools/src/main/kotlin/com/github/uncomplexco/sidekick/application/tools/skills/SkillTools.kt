package com.github.uncomplexco.sidekick.application.tools.skills

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalogProvider
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.skills.SkillCatalogReloader
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.streams.asSequence

@LLMDescription("Agent skill tools")
class SkillTools(
    private val skills: SkillCatalogProvider,
    private val virtualPaths: VirtualPaths,
    private val skillCatalogReloader: SkillCatalogReloader,
) : ToolSet {
    @Tool
    @LLMDescription("Load a skill's full instructions by skill name.")
    fun activateSkill(
        @LLMDescription("Skill name from the system prompt skills catalog.")
        name: String,
    ): String {
        val skill =
            skills
                .catalog()
                .skills
                .find { it.name == name }
                ?: throw ToolException.ValidationFailure("Unknown skill: $name")

        val skillFile = skill.folder.resolve(SKILL_FILE_NAME)
        val instructions = stripFrontmatter(Files.readString(skillFile)).trim()

        return buildString {
            appendLine("<skill_content name=\"${escapeXml(skill.name)}\">")
            appendLine(instructions)
            appendLine()
            appendLine("Skill directory: ${escapeXml(virtualPaths.virtualPath(skill.folder.toString()))}")
            appendLine("Relative paths in this skill are relative to the skill directory.")
            appendLine("<skill_resources>")
            bundledResources(skill).forEach { resource ->
                appendLine("  <file>${escapeXml(resource)}</file>")
            }
            appendLine("</skill_resources>")
            appendLine("</skill_content>")
        }
    }

    @Tool
    @LLMDescription(
        "Reload skills by re-reading extensions.json, refreshing configured extension repositories, and rebuilding the available skills catalog.",
    )
    fun reloadSkills(): ReloadSkillsResult {
        val result = skillCatalogReloader.reloadSkills()

        return ReloadSkillsResult(
            ok = true,
            total_skills = result.totalSkills,
            model_invocable_skills = result.modelInvocableSkills,
            user_invocable_skills = result.userInvocableSkills,
            skill_names = result.skillNames,
        )
    }

    private fun bundledResources(skill: Skill): List<String> =
        Files
            .walk(skill.folder)
            .use { paths ->
                paths
                    .asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.name != SKILL_FILE_NAME }
                    .map { skill.folder.relativize(it).pathString }
                    .sorted()
                    .toList()
            }

    private fun stripFrontmatter(content: String): String {
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != FRONTMATTER_DELIMITER) {
            return content
        }

        val closingDelimiterIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (closingDelimiterIndex < 0) {
            return content
        }

        return lines.drop(closingDelimiterIndex + 2).joinToString("\n")
    }

    companion object {
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val FRONTMATTER_DELIMITER = "---"
    }
}

@Serializable
data class ReloadSkillsResult(
    val ok: Boolean,
    val total_skills: Int,
    val model_invocable_skills: Int,
    val user_invocable_skills: Int,
    val skill_names: List<String>,
)
