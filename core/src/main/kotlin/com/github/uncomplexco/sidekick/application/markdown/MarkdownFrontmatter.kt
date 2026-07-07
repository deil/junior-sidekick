package com.github.uncomplexco.sidekick.application.markdown

data class MarkdownDocument(
    val frontmatter: Map<String, String>,
    val body: String,
)

fun hasMarkdownFrontmatter(markdown: String): Boolean = markdown.lines().firstOrNull()?.trim() == FRONT_MATTER_DELIMITER

fun parseMarkdownFrontmatter(markdown: String): MarkdownDocument {
    val lines = markdown.lines()
    if (!hasMarkdownFrontmatter(markdown)) {
        return MarkdownDocument(emptyMap(), markdown)
    }

    val closingDelimiterIndex = lines.drop(1).indexOfFirst { it.trim() == FRONT_MATTER_DELIMITER }
    require(closingDelimiterIndex >= 0) { "Markdown frontmatter must contain a closing delimiter" }

    val frontmatter =
        lines
            .subList(1, closingDelimiterIndex + 1)
            .mapNotNull { line ->
                val separatorIndex = line.indexOf(':')
                if (separatorIndex <= 0) {
                    null
                } else {
                    line.substring(0, separatorIndex).trim() to cleanYamlScalar(line.substring(separatorIndex + 1))
                }
            }.toMap()

    return MarkdownDocument(
        frontmatter = frontmatter,
        body = lines.drop(closingDelimiterIndex + 2).joinToString("\n"),
    )
}

private fun cleanYamlScalar(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")

private const val FRONT_MATTER_DELIMITER = "---"
