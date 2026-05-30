package com.github.uncomplexco.sidekick.application.agent

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider

internal fun formatUserMessage(
    user: String,
    text: String,
): String =
    buildString {
        appendLine("<current-message>")
        appendLine("[$user] $text")
        append("</current-message>")
    }

fun openRouterExecutor(apiKey: String): PromptExecutor =
    MultiLLMPromptExecutor(
        LLMProvider.OpenRouter to
            OpenAILLMClient(
                apiKey = apiKey,
                settings =
                    OpenAIClientSettings(
                        baseUrl = "https://openrouter.ai/api/v1",
                        chatCompletionsPath = "chat/completions",
                    ),
            ),
    )
