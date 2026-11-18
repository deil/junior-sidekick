package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.koog.KoogAgent
import com.github.uncomplexco.sidekick.application.agent.koog.RunId
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.skills.SkillsCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.context.ProjectContext
import com.github.uncomplexco.sidekick.application.context.prompts.SkillsSection.renderSkillsSection
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import org.springframework.stereotype.Component

@Component
class KoogSubagentRunner(
    private val koogConfig: KoogConfig,
    private val subagents: SubagentCatalogProvider,
    private val skills: SkillsCatalog,
) : SubagentRunner {
    override suspend fun run(
        agentId: String,
        runId: RunId,
        subagentType: String,
        prompt: String,
        tools: ToolRegistry,
        conversation: ConversationContext,
    ): AgentRunResult<String> {
        val systemPrompt =
            subagents.catalog().subagents.firstOrNull { it.name == subagentType }?.systemPrompt
                ?: throw IllegalArgumentException("Unknown subagent type: $subagentType")

        return KoogAgent(koogConfig, agentId)
            .run(
                runId = runId,
                tools = tools,
                model = koogConfig.normalProfile,
                systemPrompt = {
                    subagentSystemPrompt(
                        systemPrompt,
                        skills.catalog(),
                        conversation.virtualPaths,
                        conversation.project,
                    )
                },
                input = { prompt },
            )
    }
}

internal fun subagentSystemPrompt(
    instructions: String,
    skills: SkillCatalog,
    virtualPaths: VirtualPaths,
    project: ProjectContext,
): String =
    listOfNotNull(
            instructions,
            renderSkillsSection(skills.matchingTags(project.tags), virtualPaths),
        )
        .joinToString("\n\n")
