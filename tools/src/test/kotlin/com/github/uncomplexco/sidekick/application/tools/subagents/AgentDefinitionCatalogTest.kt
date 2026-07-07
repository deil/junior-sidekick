package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.application.markdown.parseMarkdownFrontmatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentDefinitionCatalogTest {
    @Test
    fun `loads built in agent definition`() {
        val prompt = AgentDefinitionCatalog().systemPrompt("general")

        assertTrue(prompt.startsWith("You are a general-purpose subagent."), prompt)
    }

    @Test
    fun `lists built in agent definitions with descriptions`() {
        val agents = AgentDefinitionCatalog().availableAgents()

        assertEquals(listOf("general"), agents.map { it.name })
        assertEquals(listOf("general-purpose agent for complex questions and multi-step tasks"), agents.map { it.description })
        assertTrue(agents.single().systemPrompt.startsWith("You are a general-purpose subagent."))
    }

    @Test
    fun `strips frontmatter from agent definition`() {
        val prompt =
            parseMarkdownFrontmatter(
                """
                ---
                name: general
                description: General agent
                ---
                System prompt.
                """.trimIndent(),
            ).body

        assertEquals("System prompt.", prompt)
    }

    @Test
    fun `rejects missing or unsafe agent names`() {
        assertThrows<IllegalArgumentException> { AgentDefinitionCatalog().systemPrompt("missing") }
        assertThrows<IllegalArgumentException> { AgentDefinitionCatalog().systemPrompt("../general") }
        assertFalse("../general".matches(Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-))*[a-z0-9])?$")))
    }
}
