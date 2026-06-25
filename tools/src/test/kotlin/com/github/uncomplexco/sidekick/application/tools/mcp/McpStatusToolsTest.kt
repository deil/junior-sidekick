package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationIntelligenceLevel
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class McpStatusToolsTest {
    @Test
    fun `registers status tool for each configured server and reports connected state`() =
        runBlocking {
            val ctx =
                TurnContext(
                    conversationId = ConversationId("C123", "1700000000.000"),
                    turnId = "turn",
                    currentMessageIds = listOf("m1"),
                    currentFiles = emptyList(),
                    sessionFiles = emptyList(),
                    intelligenceLevel = ConversationIntelligenceLevel.NORMAL,
                    history = ConversationHistory(emptyList(), emptyList(), hasKoogMessages = false),
                    mcpServers = listOf(TestConnectedMcpServer("grafana")),
                )
            val tools =
                McpStatusTools(
                    ctx,
                    listOf(
                        McpServerConfig(id = "grafana"),
                        McpServerConfig(id = "linear"),
                    ),
                ).asTools()

            assertEquals(listOf("get_mcp_status_grafana", "get_mcp_status_linear"), tools.map { it.name })
            assertEquals(
                JSONObject(
                    mapOf(
                        "server_id" to JSONPrimitive("grafana"),
                        "connected" to JSONPrimitive(true),
                    ),
                ),
                status(tools.single { it.name == "get_mcp_status_grafana" }),
            )
            assertEquals(
                JSONObject(
                    mapOf(
                        "server_id" to JSONPrimitive("linear"),
                        "connected" to JSONPrimitive(false),
                    ),
                ),
                status(tools.single { it.name == "get_mcp_status_linear" }),
            )
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun status(tool: ai.koog.agents.core.tools.ToolBase<*, *>): JSONObject =
        (tool as Tool<JSONObject, JSONObject>).execute(JSONObject(emptyMap()))
}

private class TestConnectedMcpServer(
    override val id: String,
) : ConnectedMcpServer {
    override val toolRegistry: ToolRegistry = ToolRegistry.EMPTY

    override suspend fun close() = Unit
}
