package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun prepareMcpToolDescriptor(
    originalToolName: String,
    descriptor: ToolDescriptor,
): ToolDescriptor {
    val jsonObjectStringField = atlassianJsonObjectStringField(originalToolName) ?: return descriptor

    return descriptor.copy(
        requiredParameters =
            descriptor.requiredParameters.map {
                stringifyAtlassianJsonObjectField(it, jsonObjectStringField)
            },
        optionalParameters =
            descriptor.optionalParameters.map {
                stringifyAtlassianJsonObjectField(it, jsonObjectStringField)
            },
    )
}

internal fun shouldExcludeMcpTool(
    serverId: String,
    toolName: String,
): Boolean = serverId.startsWith(JENKINS_SERVER_ID_PREFIX) && toolName in JENKINS_EXCLUDED_TOOLS

internal fun prepareMcpToolArguments(
    originalToolName: String,
    args: JSONObject,
    json: Json = Json.Default,
): JsonObject {
    val arguments = args.toKotlinxJsonObject()
    val jsonObjectStringField = atlassianJsonObjectStringField(originalToolName) ?: return arguments

    val fieldValue = arguments[jsonObjectStringField] ?: return arguments
    if (fieldValue !is JsonPrimitive || !fieldValue.isString) return arguments

    val parsed =
        runCatching { json.parseToJsonElement(fieldValue.content) }
            .getOrElse {
                throw IllegalArgumentException(
                    "$originalToolName $jsonObjectStringField must be a valid JSON object string",
                    it,
                )
            }
    val parsedObject =
        parsed as? JsonObject
            ?: throw IllegalArgumentException("$originalToolName $jsonObjectStringField must be a JSON object string")

    return JsonObject(arguments + (jsonObjectStringField to parsedObject))
}

private fun stringifyAtlassianJsonObjectField(
    parameter: ToolParameterDescriptor,
    fieldName: String,
): ToolParameterDescriptor {
    if (parameter.name != fieldName) return parameter
    if (!parameter.type.isUnconstrainedObject()) return parameter

    return parameter.copy(
        description =
            parameter.description.trimEnd() +
                " Pass this arbitrary JSON object as a JSON-encoded string.",
        type = ToolParameterType.String,
    )
}

private fun ToolParameterType.isUnconstrainedObject(): Boolean {
    val objectType = this as? ToolParameterType.Object ?: return false
    return objectType.properties.isEmpty() &&
        objectType.requiredProperties.isEmpty() &&
        objectType.additionalProperties == true &&
        objectType.additionalPropertiesType == null
}

private fun atlassianJsonObjectStringField(originalToolName: String): String? =
    when (originalToolName) {
        ATLASSIAN_CREATE_JIRA_ISSUE_TOOL -> ATLASSIAN_ADDITIONAL_FIELDS
        ATLASSIAN_EDIT_JIRA_ISSUE_TOOL -> ATLASSIAN_FIELDS
        else -> null
    }

private const val ATLASSIAN_CREATE_JIRA_ISSUE_TOOL = "createJiraIssue"
private const val ATLASSIAN_EDIT_JIRA_ISSUE_TOOL = "editJiraIssue"
private const val ATLASSIAN_ADDITIONAL_FIELDS = "additional_fields"
private const val ATLASSIAN_FIELDS = "fields"
private const val JENKINS_SERVER_ID_PREFIX = "jenkins"
private val JENKINS_EXCLUDED_TOOLS = setOf("triggerBuild", "updateBuild", "rebuildBuild", "replayBuild")
