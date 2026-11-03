package com.github.uncomplexco.sidekick.application.agent.skills

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SkillCatalogSelectionTest {
    private val catalog =
        SkillCatalog(
            listOf(
                skill("shared"),
                skill("engineering", setOf("engineering")),
                skill("product-a", setOf("engineering", "productA")),
                skill("support", setOf("support")),
            )
        )

    @Test
    fun `project without tags receives untagged skills`() {
        val selected = catalog.matchingTags(emptySet())

        assertEquals(listOf("shared"), selected.skills.map { it.name })
    }

    @Test
    fun `partial tag match excludes skills requiring additional tags`() {
        val selected = catalog.matchingTags(setOf("engineering"))

        assertEquals(listOf("shared", "engineering"), selected.skills.map { it.name })
    }

    @Test
    fun `all required tags match even when project has additional tags`() {
        val selected = catalog.matchingTags(setOf("engineering", "productA", "production"))

        assertEquals(listOf("shared", "engineering", "product-a"), selected.skills.map { it.name })
    }

    @Test
    fun `tag matching is case sensitive`() {
        val selected = catalog.matchingTags(setOf("Engineering", "productA"))

        assertEquals(listOf("shared"), selected.skills.map { it.name })
    }

    private fun skill(name: String, tags: Set<String> = emptySet()) =
        Skill(name, "Instructions", Path.of("/skills/$name"), false, false, tags)
}
