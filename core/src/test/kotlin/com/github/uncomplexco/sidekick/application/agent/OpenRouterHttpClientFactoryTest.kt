package com.github.uncomplexco.sidekick.application.agent

import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OpenRouterHttpClientFactoryTest {
    @Test
    fun `adds app title without dropping existing headers`() {
        // Arrange
        var capturedHeaders = emptyMap<String, String>()
        val delegate =
            object : KoogHttpClient.Factory {
                override fun create(
                    clientName: String,
                    baseUrl: String,
                    headers: Map<String, String>,
                    queryParameters: Map<String, String>,
                    requestTimeoutMillis: Long,
                    connectTimeoutMillis: Long,
                    socketTimeoutMillis: Long,
                    json: Json,
                ): KoogHttpClient {
                    capturedHeaders = headers
                    error("stop after capturing headers")
                }
            }
        val factory = OpenRouterHttpClientFactory(delegate, "Sidekick")

        // Act
        assertFails {
            factory.create(
                clientName = "test",
                headers = mapOf("Authorization" to "Bearer test-key"),
            )
        }

        // Assert
        assertEquals(
            mapOf(
                "Authorization" to "Bearer test-key",
                "X-OpenRouter-Title" to "Sidekick",
            ),
            capturedHeaders,
        )
    }
}
