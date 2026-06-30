package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.application.conversation.ConversationIntelligenceLevel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KoogConfigTest {
    @Test
    fun `openrouter params carry provider preferences`() {
        // Arrange
        val config =
            KoogConfig(
                openRouterApiKey = "test-key",
                defaultModel = "z-ai/glm-5.2",
                defaultProvider = "azure",
                defaultReasoningEffort = "medium",
                ultrathinkModel = "openai/gpt-5.4-mini",
                ultrathinkProvider = "atlas-cloud/fp8",
                ultrathinkReasoningEffort = "high",
                maxAgentIterations = 50,
            )

        // Act
        val params = config.openRouterParams(config.profile(ConversationIntelligenceLevel.NORMAL))

        // Assert
        assertEquals(listOf("azure"), params.provider?.only)
        assertEquals("medium", params.additionalProperties?.get("reasoning")?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }
}
