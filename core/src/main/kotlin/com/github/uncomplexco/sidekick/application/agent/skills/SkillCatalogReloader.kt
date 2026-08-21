package com.github.uncomplexco.sidekick.application.agent.skills

data class SkillCatalogReloadResult(
    val totalSkills: Int,
    val modelInvocableSkills: Int,
    val userInvocableSkills: Int,
    val skillNames: List<String>,
)

fun interface SkillCatalogReloader {
    fun reloadSkills(): SkillCatalogReloadResult
}
