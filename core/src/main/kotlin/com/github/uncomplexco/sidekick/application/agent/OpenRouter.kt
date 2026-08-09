package com.github.uncomplexco.sidekick.application.agent

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.toRetryingClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.json.Json

fun KoogConfig.openRouterExecutor(): PromptExecutor =
    MultiLLMPromptExecutor(
        LLMProvider.OpenRouter to
            OpenRouterLLMClient(
                apiKey = openRouterApiKey,
                httpClientFactory =
                    OpenRouterHttpClientFactory(
                        HttpClientFactoryResolver.resolve(),
                        openRouterAppTitle,
                        openRouterAppUrl,
                    ),
            ).toRetryingClient(RetryConfig.PRODUCTION),
    )

internal class OpenRouterHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val appTitle: String,
    appUrl: String?,
) : KoogHttpClient.Factory {
    private val attributionHeaders =
        appUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                mapOf(
                    "HTTP-Referer" to it,
                    "X-OpenRouter-Title" to appTitle,
                )
            }.orEmpty()

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient =
        delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers + attributionHeaders,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json,
        )
}
