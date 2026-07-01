package com.github.uncomplexco.sidekick.application.context.prompts

import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.skills.Skills.Companion.SKILL_FILE_NAME
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.AVAILABLE_SKILLS_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.ContextTags.SKILLS_SECTION_TAG
import com.github.uncomplexco.sidekick.application.utils.escapeXml
import com.github.uncomplexco.sidekick.application.utils.xmlTag

internal fun skillsSection(
    skills: SkillCatalog,
    virtualPaths: VirtualPaths,
): String? {
    val modelInvocableSkills = skills.skills.filterNot { it.disableModelInvocation }
    val userInvocableSkills = skills.skills.filter { it.userInvocable }

    if (modelInvocableSkills.isEmpty() && userInvocableSkills.isEmpty()) {
        return null
    }

    return xmlTag(
        SKILLS_SECTION_TAG,
        buildString {
            appendLine(
                """
                The following skills provide specialized instructions for specific tasks.
                When a task matches a skill's description, call the activateSkill tool with the skill's name to load its full instructions.
                User-invocable skills are listed separately. Do not activate those unless the user explicitly requested one.
                Do not answer from memory when a skill fits.
                If none fits, do not load a skill.
                """.trimIndent(),
            )
            appendLine()

            if (modelInvocableSkills.isNotEmpty()) {
                appendLine(
                    xmlTag(
                        AVAILABLE_SKILLS_TAG,
                        buildString {
                            modelInvocableSkills.forEach { skill ->
                                appendLine(renderSkill(skill, virtualPaths))
                            }
                        },
                    ),
                )
            }

            if (userInvocableSkills.isNotEmpty()) {
                appendLine(
                    xmlTag(
                        USER_INVOCABLE_SKILLS_TAG,
                        buildString {
                            userInvocableSkills.forEach { skill ->
                                appendLine(renderSkill(skill, virtualPaths))
                            }
                        },
                    ),
                )
            }
        },
    )
}

private const val USER_INVOCABLE_SKILLS_TAG = "user_invocable_skills"

internal fun renderSkill(
    skill: Skill,
    virtualPaths: VirtualPaths,
): String =
    xmlTag(
        "skill",
        buildString {
            appendLine("<name>${escapeXml(skill.name)}</name>")
            appendLine("<description>${escapeXml(skill.description)}</description>")
            appendLine(
                "<location>${
                    escapeXml(
                        virtualPaths.virtualPath(
                            skill.folder
                                .resolve(SKILL_FILE_NAME)
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        ),
                    )
                }</location>",
            )
        },
    )
