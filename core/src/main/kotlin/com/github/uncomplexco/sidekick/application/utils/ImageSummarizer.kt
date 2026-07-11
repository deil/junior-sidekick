package com.github.uncomplexco.sidekick.application.utils

import java.nio.file.Path

interface ImageSummarizer {
    suspend fun summarize(imagePath: Path): Result

    sealed interface Result {
        data class Success(
            val summary: String,
        ) : Result

        data class Failure(
            val error: Throwable,
        ) : Result
    }
}
