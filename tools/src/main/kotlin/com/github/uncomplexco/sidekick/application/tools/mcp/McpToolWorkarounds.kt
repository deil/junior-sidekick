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
    if (originalToolName != ATLASSIAN_CREATE_JIRA_ISSUE_TOOL) return descriptor

    return descriptor.copy(
        requiredParameters = descriptor.requiredParameters.map(::stringifyAtlassianAdditionalFields),
        optionalParameters = descriptor.optionalParameters.map(::stringifyAtlassianAdditionalFields),
    )
}

internal fun prepareMcpToolArguments(
    originalToolName: String,
    args: JSONObject,
    json: Json = Json.Default,
): JsonObject {
    val arguments = args.toKotlinxJsonObject()
    if (originalToolName != ATLASSIAN_CREATE_JIRA_ISSUE_TOOL) return arguments

    val additionalFields = arguments[ATLASSIAN_ADDITIONAL_FIELDS] ?: return arguments
    if (additionalFields !is JsonPrimitive || !additionalFields.isString) return arguments

    val parsed =
        runCatching { json.parseToJsonElement(additionalFields.content) }
            .getOrElse {
                throw IllegalArgumentException(
                    "createJiraIssue additional_fields must be a valid JSON object string",
                    it,
                )
            }
    val parsedObject =
        parsed as? JsonObject
            ?: throw IllegalArgumentException("createJiraIssue additional_fields must be a JSON object string")

    return JsonObject(arguments + (ATLASSIAN_ADDITIONAL_FIELDS to parsedObject))
}

private fun stringifyAtlassianAdditionalFields(parameter: ToolParameterDescriptor): ToolParameterDescriptor {
    if (parameter.name != ATLASSIAN_ADDITIONAL_FIELDS) return parameter
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

private const val ATLASSIAN_CREATE_JIRA_ISSUE_TOOL = "createJiraIssue"
private const val ATLASSIAN_ADDITIONAL_FIELDS = "additional_fields"
