package com.github.uncomplexco.sidekick.application.context

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MarkdownPromptTemplateTest {
    @ParameterizedTest
    @CsvSource("true,Use cards.,Use text.", "false,Use text.,Use cards.")
    fun `selects the configured feature branch`(
        enabled: Boolean,
        expected: String,
        excluded: String,
    ) {
        val template = MarkdownPromptTemplate(enabled)
        val markdown = "{% if features.chat_ui %}Use cards.{% else %}Use text.{% endif %}"

        val rendered = template.render(markdown, "RULES.md")

        assertEquals(expected, rendered)
        assertTrue(excluded !in rendered)
    }

    @Test
    fun `plain markdown remains unchanged`() {
        val markdown = "# Rules\n\nUse <@U123> & `code`.\n"

        val rendered = MarkdownPromptTemplate().render(markdown, "SOUL.md")

        assertEquals(markdown, rendered)
    }

    @Test
    fun `existing prompt variables are evaluated without HTML escaping`() {
        val template = MarkdownPromptTemplate()

        val rendered =
            template.render(
                "Return {{NO_REPLY_MARKER}} & {{ mention }}.",
                "RULES.md",
                mapOf("NO_REPLY_MARKER" to "[[NO_REPLY]]", "mention" to "<@U123>"),
            )

        assertEquals("Return [[NO_REPLY]] & <@U123>.", rendered)
    }

    @Test
    fun `unknown feature names report the source file instead of silently disabling instructions`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MarkdownPromptTemplate()
                    .render("{% if features.typo %}Use cards.{% endif %}", "config/RULES.md")
            }

        assertTrue(failure.message.orEmpty().contains("config/RULES.md"))
    }

    @Test
    fun `malformed conditionals report the source file`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MarkdownPromptTemplate()
                    .render("{% if features.chat_ui %}Missing endif", "config/WORLD.md")
            }

        assertTrue(failure.message.orEmpty().contains("config/WORLD.md"))
    }

    @Test
    fun `application environment is not available to templates`() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownPromptTemplate().render("{{ environment }}", "RULES.md")
        }
    }
}
