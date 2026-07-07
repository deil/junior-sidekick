package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.application.markdown.parseMarkdownFrontmatter
import org.springframework.stereotype.Component

@Component
class AgentDefinitionCatalog(
    private val classLoader: ClassLoader = AgentDefinitionCatalog::class.java.classLoader,
) {
    fun availableAgents(): List<AgentDefinition> = BUILT_IN_AGENTS.map { load(it) }

    fun systemPrompt(name: String): String = load(name).systemPrompt

    private fun load(name: String): AgentDefinition {
        require(AGENT_NAME_RE.matches(name)) { "Unknown subagent type: $name" }

        val resource = classLoader.getResource("agents/$name.md") ?: throw IllegalArgumentException("Unknown subagent type: $name")
        val document = parseMarkdownFrontmatter(resource.readText())
        return AgentDefinition(
            name = name,
            description = document.frontmatter["description"] ?: "No description.",
            systemPrompt = document.body,
        )
    }

    companion object {
        private val BUILT_IN_AGENTS = listOf("general")
        private val AGENT_NAME_RE = Regex("^[a-z0-9](?:(?:[a-z0-9]|-(?!-))*[a-z0-9])?$")
    }
}

data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
)
