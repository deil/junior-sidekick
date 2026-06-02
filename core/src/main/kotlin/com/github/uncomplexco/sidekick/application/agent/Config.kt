package com.github.uncomplexco.sidekick.application.agent

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.llm.LLMCapability
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

@Configuration
class AgentConfig(
    @Value($$"${agent.name}") val name: String,
    @Value($$"${agent.state-directory}") val stateDir: String,
) {
    var botUsername: String? = null

    fun stateDirectoryPath(): Path {
        val path = Path.of(stateDir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent state directory is not a directory: $path" }
        return path
    }
}

@Configuration
class KoogConfig(
    @Value($$"${adapters.open-router.api-key}")
    val openRouterApiKey: String,
) {
    val provider = "google-vertex"
    val model = "google/gemini-3-flash-preview"

    fun openRouterParams(): OpenAIChatParams =
        OpenAIChatParams(
            additionalProperties =
                mapOf(
                    "provider" to
                        buildJsonObject {
                            put(
                                "only",
                                buildJsonArray {
                                    add(JsonPrimitive(provider))
                                },
                            )
                        },
                ),
        )

    fun modelCapabilities(): List<LLMCapability> =
        listOf(
            LLMCapability.Tools,
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
        )
}
