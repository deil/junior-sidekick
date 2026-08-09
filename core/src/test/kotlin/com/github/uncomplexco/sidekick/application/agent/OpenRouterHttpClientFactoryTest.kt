package com.github.uncomplexco.sidekick.application.agent

import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OpenRouterHttpClientFactoryTest {
    @Test
    fun `adds app attribution without dropping existing headers`() {
        // Arrange
        val delegate = CapturingFactory()
        val factory = OpenRouterHttpClientFactory(delegate, "Sidekick", " https://github.com/deil/junior-sidekick ")

        // Act
        createClient(factory)

        // Assert
        assertEquals(
            mapOf(
                "Authorization" to "Bearer test-key",
                "HTTP-Referer" to "https://github.com/deil/junior-sidekick",
                "X-OpenRouter-Title" to "Sidekick",
            ),
            delegate.headers,
        )
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" "])
    fun `omits app attribution without app url`(appUrl: String?) {
        // Arrange
        val delegate = CapturingFactory()
        val factory = OpenRouterHttpClientFactory(delegate, "Sidekick", appUrl)

        // Act
        createClient(factory)

        // Assert
        assertEquals(mapOf("Authorization" to "Bearer test-key"), delegate.headers)
    }

    private fun createClient(factory: OpenRouterHttpClientFactory) {
        assertFails {
            factory.create(
                clientName = "test",
                headers = mapOf("Authorization" to "Bearer test-key"),
            )
        }
    }

    private class CapturingFactory : KoogHttpClient.Factory {
        var headers = emptyMap<String, String>()

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
            this.headers = headers
            error("stop after capturing headers")
        }
    }
}
