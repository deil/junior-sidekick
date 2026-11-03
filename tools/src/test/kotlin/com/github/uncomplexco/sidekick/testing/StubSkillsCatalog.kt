package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.skills.SkillsCatalog

class StubSkillsCatalog(
    private var current: SkillCatalog = SkillCatalog(emptyList()),
    private val reloaded: SkillCatalog = current,
) : SkillsCatalog {
    override fun catalog(): SkillCatalog = current

    override fun reload(): SkillCatalog {
        current = reloaded
        return current
    }
}
