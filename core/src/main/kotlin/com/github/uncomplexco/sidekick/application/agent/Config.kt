package com.github.uncomplexco.sidekick.application.agent

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.clients.openrouter.models.ProviderPreferences
import ai.koog.prompt.llm.LLMCapability
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.agent.workspace.WorkspaceLayout
import kotlinx.serialization.json.JsonPrimitive
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

    fun workspaceLayout(): WorkspaceLayout = WorkspaceLayout(Path.of(workingDir))
}

@Configuration
class KoogConfig(
    @Value($$"${adapters.open-router.api-key}")
    val openRouterApiKey: String,
    @Value($$"${agent.llm.fast.model}")
    private val fastModel: String,
    @Value($$"${agent.llm.fast.provider}")
    private val fastProvider: String,
    @Value($$"${agent.llm.fast.reasoning-effort}")
    private val fastReasoningEffort: String,
    @Value($$"${agent.llm.default.model}")
    private val defaultModel: String,
    @Value($$"${agent.llm.default.provider}")
    private val defaultProvider: String,
    @Value($$"${agent.llm.default.reasoning-effort:medium}")
    private val defaultReasoningEffort: String,
    @Value($$"${agent.llm.ultrathink.model}")
    private val ultrathinkModel: String,
    @Value($$"${agent.llm.ultrathink.provider}")
    private val ultrathinkProvider: String,
    @Value($$"${agent.llm.ultrathink.reasoning-effort:high}")
    private val ultrathinkReasoningEffort: String,
    @Value($$"${agent.llm.image.model}")
    val imageModel: String,
    @Value($$"${agent.llm.max-agent-iterations:50}")
    val maxAgentIterations: Int,
) {
    val fastProfile: LlmProfile
        get() =
            LlmProfile(
                model = fastModel,
                provider = fastProvider,
                reasoningEffort = parseReasoningEffort(fastReasoningEffort),
            )

    val normalProfile: LlmProfile
        get() =
            LlmProfile(
                model = defaultModel,
                provider = defaultProvider,
                reasoningEffort = parseReasoningEffort(defaultReasoningEffort),
            )

    val ultrathinkProfile: LlmProfile
        get() =
            LlmProfile(
                model = ultrathinkModel,
                provider = ultrathinkProvider,
                reasoningEffort = parseReasoningEffort(ultrathinkReasoningEffort),
            )

    fun profile(level: AiModelProfile): LlmProfile =
        when (level) {
            AiModelProfile.FAST -> fastProfile
            AiModelProfile.NORMAL -> normalProfile
            AiModelProfile.ULTRATHINK -> ultrathinkProfile
        }

    fun openRouterParams(profile: LlmProfile): OpenRouterParams =
        OpenRouterParams(
            provider =
                ProviderPreferences(
                    only = listOf(profile.provider),
                ),
            additionalProperties =
                mapOf(
                    "reasoning" to
                        buildJsonObject {
                            put("effort", JsonPrimitive(profile.reasoningEffort.name.lowercase()))
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

data class LlmProfile(
    val model: String,
    val provider: String,
    val reasoningEffort: ReasoningEffort,
)

private fun parseReasoningEffort(value: String): ReasoningEffort = ReasoningEffort.valueOf(value.trim().uppercase().replace('-', '_'))
