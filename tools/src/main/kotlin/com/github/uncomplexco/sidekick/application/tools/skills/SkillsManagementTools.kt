package com.github.uncomplexco.sidekick.application.tools.skills

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.agent.skills.SkillsCatalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@LLMDescription("Manage available skills.")
class SkillsManagementTools(private val skills: SkillsCatalog) : ToolSet {
    @Tool
    @LLMDescription(
        "Load the latest skills from configured sources, picking up added, updated, or removed skills."
    )
    fun reloadSkills(): ReloadSkillsResult {
        val catalog = skills.reload()

        return ReloadSkillsResult(
            ok = true,
            totalSkills = catalog.skills.size,
            modelInvocableSkills = catalog.skills.count { !it.disableModelInvocation },
            userInvocableSkills = catalog.skills.count { it.userInvocable },
            skillNames = catalog.skills.map { it.name }.sorted(),
        )
    }
}

@Serializable
data class ReloadSkillsResult(
    val ok: Boolean,
    @SerialName("total_skills") val totalSkills: Int,
    @SerialName("model_invocable_skills") val modelInvocableSkills: Int,
    @SerialName("user_invocable_skills") val userInvocableSkills: Int,
    @SerialName("skill_names") val skillNames: List<String>,
)
