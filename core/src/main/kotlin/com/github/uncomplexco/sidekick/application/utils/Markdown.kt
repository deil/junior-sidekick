package com.github.uncomplexco.sidekick.application.utils

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

private const val FRONTMATTER_DELIMITER = "---"

data class MarkdownDocument(
    val frontmatter: Map<String, Any?>,
    val body: String,
)

fun hasMarkdownFrontmatter(markdown: String): Boolean =
    markdown.lines().firstOrNull()?.trim() == FRONTMATTER_DELIMITER

fun parseMarkdownFrontmatter(markdown: String): MarkdownDocument {
    val lines = markdown.lines()
    if (!hasMarkdownFrontmatter(markdown)) {
        return MarkdownDocument(emptyMap(), markdown)
    }

    val closingDelimiterIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
    require(closingDelimiterIndex >= 0) { "Markdown frontmatter must contain a closing delimiter" }

    return MarkdownDocument(
        frontmatter = parseYaml(lines.subList(1, closingDelimiterIndex + 1).joinToString("\n")),
        body = lines.drop(closingDelimiterIndex + 2).joinToString("\n"),
    )
}

fun parseYaml(yaml: String): Map<String, Any?> {
    val document =
        try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml)
        } catch (error: YAMLException) {
            throw IllegalArgumentException("Invalid YAML", error)
        } ?: return emptyMap()

    require(document is Map<*, *>) { "YAML document must be a mapping" }
    return document.entries.associate { (key, value) ->
        require(key is String) { "YAML mapping keys must be strings" }
        key to value
    }
}
