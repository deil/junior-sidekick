package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.model.block.DividerBlock
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.block.composition.MarkdownTextObject
import com.slack.api.model.block.composition.PlainTextObject

internal object SlackHomeDescriptionBlocks {
    private val heading = Regex("^#{1,6}\\s+(.+)$")

    fun fromMarkdown(markdown: String): List<LayoutBlock> =
        markdown
            .split(Regex("\\n{2,}"))
            .flatMap { chunk -> chunk.trim().toBlocks() }
            .ifEmpty { listOf(section("DESCRIPTION.md is empty.")) }

    private fun String.toBlocks(): List<LayoutBlock> =
        when {
            isBlank() -> emptyList()
            this == "---" -> listOf(DividerBlock.builder().build())
            heading.matches(this) -> listOf(header(heading.matchEntire(this)!!.groupValues[1]))
            else -> toSlackMrkdwn().chunked(SECTION_MAX_CHARS).map { section(it) }
        }

    private fun header(text: String): HeaderBlock =
        HeaderBlock.builder().text(PlainTextObject.builder().text(text.take(HEADER_MAX_CHARS)).emoji(true).build()).build()

    private fun section(text: String): SectionBlock =
        SectionBlock.builder().text(MarkdownTextObject.builder().text(text).build()).build()

    private fun String.toSlackMrkdwn(): String =
        replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<$2|$1>")
            .replace(Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")) { match ->
                "*${match.groupValues[1].ifBlank { match.groupValues[2] }}*"
            }

    private const val HEADER_MAX_CHARS = 150
    private const val SECTION_MAX_CHARS = 3_000
}
