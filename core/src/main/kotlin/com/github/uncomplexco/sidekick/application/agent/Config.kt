package com.github.uncomplexco.sidekick.application.agent

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
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
    @Value($$"${agent.working-directory}") val workingDir: String,
) {
    var botUsername: String? = null

    fun stateDirectoryPath(): Path {
        val path = Path.of(stateDir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent state directory is not a directory: $path" }
        return path
    }

    fun workingDirectoryPath(): Path {
        val path = Path.of(workingDir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent working directory is not a directory: $path" }
        return path
    }

    fun skillsDirectoryPath(): Path {
        val path = workingDirectoryPath().resolve("skills")
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent skills directory is not a directory: $path" }
        return path
    }

    fun globalDirectoryPath(): Path {
        val path = workingDirectoryPath().resolve("global")
        Files.createDirectories(path)
        require(Files.isDirectory(path)) { "Configured agent global directory is not a directory: $path" }
        return path
    }
}

@Configuration
class KoogConfig(
    @Value($$"${adapters.open-router.api-key}")
    val openRouterApiKey: String,
) {
    val provider = "azure"
    val model = "openai/gpt-5.4-mini"
    val reasoningEffort = ReasoningEffort.MEDIUM

    fun openRouterParams(): OpenAIChatParams =
        OpenAIChatParams(
            reasoningEffort = reasoningEffort,
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
