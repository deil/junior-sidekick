package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class McpToolArgumentValidationTest {
    @Test
    fun `leaves missing Atlassian object field absent`() {
        val args = JSONObject(mapOf("summary" to JSONPrimitive("Bug")))

        val prepared = prepareMcpToolArguments("createJiraIssue", args)

        assertEquals(JsonObject(mapOf("summary" to JsonPrimitive("Bug"))), prepared)
    }

    @Test
    fun `leaves non-string Atlassian field unchanged`() {
        val args = JSONObject(mapOf("fields" to JSONPrimitive(42)))

        val prepared = prepareMcpToolArguments("editJiraIssue", args)

        assertEquals(JsonPrimitive(42), prepared["fields"])
    }

    @Test
    fun `rejects malformed JSON string with original cause`() {
        val args = JSONObject(mapOf("fields" to JSONPrimitive("{")))

        val error =
            assertFailsWith<IllegalArgumentException> {
                prepareMcpToolArguments("editJiraIssue", args)
            }

        assertEquals("editJiraIssue fields must be a valid JSON object string", error.message)
        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test
    fun `rejects JSON string containing an array instead of an object`() {
        val args = JSONObject(mapOf("fields" to JSONPrimitive("[]")))

        val error =
            assertFailsWith<IllegalArgumentException> {
                prepareMcpToolArguments("editJiraIssue", args)
            }

        assertEquals("editJiraIssue fields must be a JSON object string", error.message)
    }
}
