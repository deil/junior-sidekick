package com.github.uncomplexco.sidekick.application.tools.subagents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TaskOutputSchema(val json: JsonObject) {
    init {
        require(json["type"] == JsonPrimitive("object")) { "output_schema must describe an object" }
        validate(json, depth = 0)
    }

    fun parse(output: String): JsonObject? {
        val value =
            try {
                Json.parseToJsonElement(output) as? JsonObject ?: return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        return value.takeIf { matches(json, it) }
    }

    private fun validate(schema: JsonObject, depth: Int) {
        require(depth <= 12) { "output_schema is nested too deeply" }
        val type = typeOf(schema)
        val keywords =
            when (type) {
                "object" -> setOf("properties", "required", "additionalProperties")
                "array" -> setOf("items")
                "string",
                "boolean",
                "integer",
                "number",
                "null" -> emptySet()
                else -> throw IllegalArgumentException("Unsupported output_schema type")
            }
        require(schema.keys.all { it in keywords || it == "type" || it == "description" }) {
            "Unsupported output_schema keyword for $type"
        }
        require(
            "description" !in schema || (schema["description"] as? JsonPrimitive)?.isString == true
        ) {
            "output_schema descriptions must be strings"
        }
        when (type) {
            "object" -> {
                val properties =
                    schema["properties"] as? JsonObject
                        ?: throw IllegalArgumentException("Object schemas need properties")
                val required =
                    schema["required"] as? JsonArray
                        ?: throw IllegalArgumentException("Object schemas need required")
                require(
                    required.size == properties.size &&
                        required.toSet() == properties.keys.map(::JsonPrimitive).toSet() &&
                        schema["additionalProperties"] == JsonPrimitive(false)
                ) {
                    "Object schemas must require all properties and disallow additional properties"
                }
                properties.values.forEach { validate(child(it), depth + 1) }
            }
            "array" -> validate(child(schema["items"]), depth + 1)
        }
    }

    private fun matches(schema: JsonObject, value: JsonElement): Boolean =
        when (typeOf(schema)) {
            "object" -> {
                val properties = schema.getValue("properties") as JsonObject
                value is JsonObject &&
                    value.keys == properties.keys &&
                    properties.all { (key, property) ->
                        matches(child(property), value.getValue(key))
                    }
            }
            "array" -> value is JsonArray && value.all { matches(child(schema["items"]), it) }
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value == JsonPrimitive(true) || value == JsonPrimitive(false)
            "null" -> value == JsonNull
            "number" -> number(value) != null
            "integer" -> number(value)?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
            else -> false
        }

    private fun number(value: JsonElement) =
        (value as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.content
            ?.takeIf(JSON_NUMBER_RE::matches)
            ?.toBigDecimalOrNull()

    private fun typeOf(schema: JsonObject): String? =
        (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun child(value: JsonElement?): JsonObject =
        value as? JsonObject
            ?: throw IllegalArgumentException("Expected an object in output_schema")

    private companion object {
        val JSON_NUMBER_RE = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
