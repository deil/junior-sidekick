package com.github.uncomplexco.sidekick.application.agent.skills

interface SkillsCatalog {
    fun catalog(): SkillCatalog

    fun reload(): SkillCatalog
}
