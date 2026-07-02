package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpToolWorkaroundsTest {
    @Test
    fun `reports Atlassian create issue additional fields as string`() {
        val descriptor =
            ToolDescriptor(
                name = "createJiraIssue",
                description = "Create Jira issue",
                requiredParameters =
                    listOf(
                        unconstrainedObjectParameter("additional_fields", "REQUIRED for custom fields."),
                    ),
            )

        val prepared = prepareMcpToolDescriptor("createJiraIssue", descriptor)

        val parameter = prepared.requiredParameters.single()
        assertEquals(ToolParameterType.String, parameter.type)
        assertEquals(
            "REQUIRED for custom fields. Pass this arbitrary JSON object as a JSON-encoded string.",
            parameter.description,
        )
    }

    @Test
    fun `reports Atlassian edit issue fields as string`() {
        val descriptor =
            ToolDescriptor(
                name = "editJiraIssue",
                description = "Edit Jira issue",
                requiredParameters =
                    listOf(
                        unconstrainedObjectParameter("fields", "Fields to update."),
                    ),
            )

        val prepared = prepareMcpToolDescriptor("editJiraIssue", descriptor)

        val parameter = prepared.requiredParameters.single()
        assertEquals(ToolParameterType.String, parameter.type)
        assertEquals(
            "Fields to update. Pass this arbitrary JSON object as a JSON-encoded string.",
            parameter.description,
        )
    }

    @Test
    fun `parses Atlassian create issue additional fields string before MCP call`() {
        val args =
            JSONObject(
                mapOf(
                    "summary" to JSONPrimitive("Fix bug"),
                    "additional_fields" to JSONPrimitive("{\"customfield_123\":\"321\",\"labels\":[\"bug\"]}"),
                ),
            )

        val prepared = prepareMcpToolArguments("createJiraIssue", args)

        val additionalFields = assertIs<JsonObject>(prepared["additional_fields"])
        assertEquals(JsonPrimitive("321"), additionalFields["customfield_123"])
    }

    @Test
    fun `parses Atlassian edit issue fields string before MCP call`() {
        val args =
            JSONObject(
                mapOf(
                    "issue_key" to JSONPrimitive("ABC-123"),
                    "fields" to JSONPrimitive("{\"customfield_123\":\"321\",\"labels\":[\"bug\"]}"),
                ),
            )

        val prepared = prepareMcpToolArguments("editJiraIssue", args)

        val fields = assertIs<JsonObject>(prepared["fields"])
        assertEquals(JsonPrimitive("321"), fields["customfield_123"])
    }

    @Test
    fun `leaves other MCP tool arguments unchanged`() {
        val args = JSONObject(mapOf("additional_fields" to JSONPrimitive("{\"x\":1}")))

        val prepared = prepareMcpToolArguments("otherTool", args)

        assertEquals(JsonPrimitive("{\"x\":1}"), prepared["additional_fields"])
    }

    @Test
    fun `excludes destructive Jenkins MCP tools`() {
        listOf("triggerBuild", "updateBuild", "rebuildBuild", "replayBuild").forEach { toolName ->
            assertTrue(shouldExcludeMcpTool("jenkins", toolName), toolName)
            assertTrue(shouldExcludeMcpTool("jenkins-prod", toolName), toolName)
        }
    }

    @Test
    fun `does not exclude Jenkins read tools`() {
        assertFalse(shouldExcludeMcpTool("jenkins", "getBuild"))
    }

    @Test
    fun `does not exclude tools from non Jenkins servers`() {
        assertFalse(shouldExcludeMcpTool("ci", "triggerBuild"))
    }

    private fun unconstrainedObjectParameter(
        name: String,
        description: String,
    ) = ToolParameterDescriptor(
        name = name,
        description = description,
        type =
            ToolParameterType.Object(
                properties = emptyList(),
                requiredProperties = emptyList(),
                additionalProperties = true,
                additionalPropertiesType = null,
            ),
    )
}
