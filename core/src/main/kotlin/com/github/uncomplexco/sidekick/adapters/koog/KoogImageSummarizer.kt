package com.github.uncomplexco.sidekick.adapters.koog

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.context.prompts.Prompts
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlinx.io.files.Path as KotlinPath

@Component
class KoogImageSummarizer(
    private val config: KoogConfig,
) : ImageSummarizer {
    private val model =
        LLModel(
            provider = LLMProvider.OpenRouter,
            id = config.imageModel,
            capabilities =
                listOf(
                    LLMCapability.Completion,
                    LLMCapability.Vision.Image,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
        )

    override suspend fun summarize(imagePath: Path): ImageSummarizer.Result {
        val prompt =
            prompt(
                id = "sidekick-image-summarization",
                params = LLMParams(temperature = 0.0),
            ) {
                user {
                    text(Prompts.IMAGE_SUMMARIZATION_PROMPT)
                    image(KotlinPath(imagePath.toString()))
                }
            }

        return runCatching {
            openRouterExecutor(config.openRouterApiKey).use { executor ->
                executor.execute(prompt, model).textContent()
            }
        }.fold(
            onSuccess = ImageSummarizer.Result::Success,
            onFailure = ImageSummarizer.Result::Failure,
        )
    }
}
