package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.utils.parseMarkdownFrontmatter
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.stereotype.Component

data class ProjectContext(
    val tags: Set<String> = emptySet(),
    val instructions: String? = null,
)

@Component
class ProjectContexts {
    private companion object {
        const val PROJECT_INSTRUCTIONS_FILE = "AGENTS.md"
    }

    fun resolve(projectRoot: Path): ProjectContext {
        val file = projectRoot.resolve(PROJECT_INSTRUCTIONS_FILE)
        if (!Files.isRegularFile(file)) return ProjectContext()

        return try {
            val document = parseMarkdownFrontmatter(Files.readString(file))
            val tags = document.frontmatter["tags"] ?: emptyList<String>()
            require(tags is List<*> && tags.all { it is String }) {
                "tags must be a list of strings"
            }

            ProjectContext(
                tags = tags.map { it as String }.toSet(),
                instructions = document.body.trimEnd(),
            )
        } catch (error: IllegalArgumentException) {
            throw ProjectConfigurationException(
                "Invalid project configuration in $PROJECT_INSTRUCTIONS_FILE: ${error.message}",
                error,
            )
        }
    }
}

class ProjectConfigurationException(message: String, cause: IllegalArgumentException) :
    IllegalArgumentException(message, cause)
