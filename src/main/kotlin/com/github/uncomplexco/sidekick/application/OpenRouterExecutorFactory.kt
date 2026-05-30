package com.github.uncomplexco.sidekick.application

import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders

private val openRouterRequestLogger = KotlinLogging.logger {}

fun openRouterExecutor(apiKey: String): SingleLLMPromptExecutor {
    val baseClient =
        HttpClient {
            /*
            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            openRouterRequestLogger.info { "OpenRouter HTTP: $message" }
                        }
                    }
                level = LogLevel.BODY
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }
             */
        }

    return SingleLLMPromptExecutor(
        OpenRouterLLMClient(
            apiKey = apiKey,
            baseClient = baseClient,
        ),
    )
}
