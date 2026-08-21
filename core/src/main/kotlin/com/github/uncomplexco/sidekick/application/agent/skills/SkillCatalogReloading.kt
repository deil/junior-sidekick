package com.github.uncomplexco.sidekick.application.agent.skills

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.springframework.stereotype.Component

@Component
class ConfiguredSkillCatalogReloader(
    private val config: AgentConfig,
    private val skills: Skills,
) : SkillCatalogReloader {
    override fun reloadSkills(): SkillCatalogReloadResult = skills.syncAndScan(config).toReloadResult()
}

private fun SkillCatalog.toReloadResult(): SkillCatalogReloadResult =
    SkillCatalogReloadResult(
        totalSkills = skills.size,
        modelInvocableSkills = skills.count { !it.disableModelInvocation },
        userInvocableSkills = skills.count { it.userInvocable },
        skillNames = skills.map { it.name }.sorted(),
    )
