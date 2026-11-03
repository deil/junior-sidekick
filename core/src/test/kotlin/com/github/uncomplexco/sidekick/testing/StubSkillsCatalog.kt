package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.skills.SkillsCatalog

class StubSkillsCatalog(private val current: SkillCatalog = SkillCatalog(emptyList())) :
    SkillsCatalog {
    override fun catalog(): SkillCatalog = current

    override fun reload(): SkillCatalog = current
}
