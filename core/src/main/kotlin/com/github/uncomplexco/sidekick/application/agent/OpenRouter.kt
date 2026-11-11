package com.github.uncomplexco.sidekick.application.agent

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryablePattern
import ai.koog.prompt.executor.clients.retry.toRetryingClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import kotlinx.serialization.json.Json

internal val OPEN_ROUTER_RETRY_CONFIG =
    RetryConfig.PRODUCTION.copy(
        maxAttempts = 5,
        retryablePatterns =
            RetryConfig.PRODUCTION.retryablePatterns +
                RetryablePattern.Regex(Regex("^Unexpected JSON token at offset \\d+:")),
    )

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
                )
                .toRetryingClient(OPEN_ROUTER_RETRY_CONFIG)
    )

internal class OpenRouterHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val appTitle: String,
    appUrl: String?,
) : KoogHttpClient.Factory {
    private val attributionHeaders = openRouterAttributionHeaders(appTitle, appUrl)

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

internal fun openRouterAttributionHeaders(appTitle: String, appUrl: String?): Map<String, String> =
    appUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
            mapOf(
                "HTTP-Referer" to it,
                "X-OpenRouter-Title" to appTitle,
            )
        }
        .orEmpty()
