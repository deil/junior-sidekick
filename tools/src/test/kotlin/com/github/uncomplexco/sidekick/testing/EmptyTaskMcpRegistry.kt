package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.agent.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.agent.koog.McpServersRegistry
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import java.nio.file.Path

object EmptyTaskMcpRegistry : McpServersRegistry {
    override suspend fun connect(
        conversationId: ConversationId,
        userId: String?,
        workRoot: Path,
    ): List<ConnectedMcpServer> = emptyList()
}
