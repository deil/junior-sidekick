package com.github.uncomplexco.sidekick.application.agent.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.TurnContext

enum class AgentToolRole {
    SIDEKICK,
    TASK,
    LOOP,
}

enum class ToolAccess {
    SHARED,
    ORCHESTRATOR_ONLY;

    fun permits(role: AgentToolRole): Boolean = this == SHARED || role == AgentToolRole.SIDEKICK
}

/** A tool or group of tools with the same access policy. Binding creates execution-local tools. */
data class ToolRegistration<C>(
    val id: String,
    val access: ToolAccess,
    val bind: (C) -> List<ToolBase<*, *>>,
)

data class ToolExecutionContext(
    val turn: TurnContext,
    val chat: ChatPlatformAdapter,
    val replyAttachments: ReplyAttachmentCollector,
    val onSubagentCompleted: (AgentUsageStats) -> Unit,
)

fun interface AgentToolCatalog {
    suspend fun bind(role: AgentToolRole, context: ToolExecutionContext): ToolRegistry
}
