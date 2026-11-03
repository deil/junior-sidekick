package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.context.ProjectContext
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubagentSystemPromptTest {
    @TempDir lateinit var root: Path

    @Test
    fun `system prompt appends skills after agent instructions with a blank line`() {
        // Arrange
        val instructions = "You are a research subagent.\nReturn evidence to your caller."
        val catalog = SkillCatalog(listOf(skill("research", "Find evidence & sources.")))

        // Act
        val prompt = subagentSystemPrompt(instructions, catalog, virtualPaths(), ProjectContext())

        // Assert
        assertTrue(prompt.startsWith("$instructions\n\n<skills>"), prompt)
        assertContains(prompt, "<available_skills>")
        assertContains(prompt, "<name>research</name>")
        assertContains(prompt, "<description>Find evidence &amp; sources.</description>")
        assertContains(prompt, "<location>/data/skills/repo/research/SKILL.md</location>")
        assertContains(prompt, "call the activateSkill tool with the skill's name")
        assertTrue(prompt.endsWith("</skills>"), prompt)
    }

    @Test
    fun `empty skills catalog preserves instructions exactly`() {
        val instructions = "You are a subagent.\n\nKeep these instructions intact.\n"

        val prompt =
            subagentSystemPrompt(
                instructions,
                SkillCatalog(emptyList()),
                virtualPaths(),
                ProjectContext(),
            )

        assertEquals(instructions, prompt)
    }

    @Test
    fun `user only skills are listed with explicit invocation requirement`() {
        val catalog =
            SkillCatalog(
                listOf(
                    skill("release", "Publish a release.", true, true),
                    skill("review", "Review a release.", true, true),
                )
            )

        val prompt =
            subagentSystemPrompt("General instructions", catalog, virtualPaths(), ProjectContext())

        assertContains(prompt, "<user_invocable_skills>")
        assertContains(prompt, "<user_invocable_skills>\nrelease\nreview\n</user_invocable_skills>")
        assertFalse(prompt.contains("Publish a release."), prompt)
        assertFalse(prompt.contains("Review a release."), prompt)
        assertFalse(prompt.contains("<location>"), prompt)
        assertContains(prompt, "Do not activate those unless the user explicitly requested one.")
        assertFalse(prompt.contains("<available_skills>"), prompt)
    }

    @Test
    fun `catalog with no invocable skills preserves instructions exactly`() {
        val catalog = SkillCatalog(listOf(skill("hidden", "Hidden skill.", true, false)))

        val prompt =
            subagentSystemPrompt("General instructions", catalog, virtualPaths(), ProjectContext())

        assertEquals("General instructions", prompt)
    }

    @Test
    fun `subagent catalog selects untagged and fully matching skills from project context`() {
        val catalog =
            SkillCatalog(
                listOf(
                    skill("shared", "Shared skill."),
                    skill("matching", "Matching skill.", tags = setOf("engineering", "productA")),
                    skill("partial", "Partial match.", tags = setOf("engineering", "productB")),
                    skill("hidden-user", "Unrelated user skill.", true, true, setOf("support")),
                )
            )

        val prompt =
            subagentSystemPrompt(
                "General instructions",
                catalog,
                virtualPaths(),
                ProjectContext(tags = setOf("engineering", "productA", "production")),
            )

        assertContains(prompt, "<name>shared</name>")
        assertContains(prompt, "<name>matching</name>")
        assertFalse(prompt.contains("Partial match."), prompt)
        assertFalse(prompt.contains("hidden-user"), prompt)
    }

    private fun skill(
        name: String,
        description: String,
        disableModelInvocation: Boolean = false,
        userInvocable: Boolean = false,
        tags: Set<String> = emptySet(),
    ) =
        Skill(
            name,
            description,
            root.resolve("skills/repo/$name"),
            disableModelInvocation,
            userInvocable,
            tags,
        )

    private fun virtualPaths() =
        VirtualPaths(
            root.resolve("session"),
            root.resolve("skills"),
            root.resolve("global"),
            root.resolve("work"),
            root.resolve("project"),
        )
}
