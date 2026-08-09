package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class KoogConfigTest {
    @Test
    fun `openrouter params carry provider preferences`() {
        // Arrange
        val config = testConfig()

        // Act
        val params = config.openRouterParams(config.profile(AiModelProfile.NORMAL))

        // Assert
        assertEquals(listOf("azure"), params.provider?.only)
        assertEquals(
            "medium",
            params.additionalProperties
                ?.get("reasoning")
                ?.jsonObject
                ?.get("effort")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `openrouter params carry session id`() {
        // Arrange
        val config = testConfig()

        // Act
        val params = config.openRouterParams(config.normalProfile, "channel:thread")

        // Assert
        assertEquals("channel:thread", params.additionalProperties?.get("session_id")?.jsonPrimitive?.content)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "minimal", "low", "medium", "high", "xhigh", "max"])
    fun `openrouter params carry every configured reasoning effort`(effort: String) {
        // Arrange
        val config = testConfig(defaultReasoningEffort = effort)

        // Act
        val params = config.openRouterParams(config.normalProfile)

        // Assert
        assertEquals(
            effort,
            params.additionalProperties
                ?.get("reasoning")
                ?.jsonObject
                ?.get("effort")
                ?.jsonPrimitive
                ?.content,
        )
    }

    private fun testConfig(defaultReasoningEffort: String = "medium") =
        KoogConfig(
            openRouterApiKey = "test-key",
            openRouterAppTitle = "Sidekick",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = defaultReasoningEffort,
            ultrathinkModel = "openai/gpt-5.4-mini",
            ultrathinkProvider = "atlas-cloud/fp8",
            ultrathinkReasoningEffort = "high",
            imageModel = "image-model",
            maxAgentIterations = 50,
        )
}
