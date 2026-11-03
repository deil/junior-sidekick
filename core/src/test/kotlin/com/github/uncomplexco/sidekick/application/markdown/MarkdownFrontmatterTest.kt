package com.github.uncomplexco.sidekick.application.markdown

import com.github.uncomplexco.sidekick.application.utils.hasMarkdownFrontmatter
import com.github.uncomplexco.sidekick.application.utils.parseMarkdownFrontmatter
import com.github.uncomplexco.sidekick.application.utils.parseYaml
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarkdownFrontmatterTest {
    @Test
    fun `parses frontmatter and body`() {
        val document =
            parseMarkdownFrontmatter(
                """
                ---
                name: general
                description: "General agent"
                quoted: 'yes'
                ---
                Body.
                """
                    .trimIndent()
            )

        assertEquals("general", document.frontmatter["name"])
        assertEquals("General agent", document.frontmatter["description"])
        assertEquals("yes", document.frontmatter["quoted"])
        assertEquals("Body.", document.body.trim())
    }

    @Test
    fun `returns full body when frontmatter is absent`() {
        val document = parseMarkdownFrontmatter("Body only.")

        assertEquals(emptyMap(), document.frontmatter)
        assertEquals("Body only.", document.body)
        assertFalse(hasMarkdownFrontmatter("Body only."))
    }

    @Test
    fun `rejects unclosed frontmatter`() {
        assertTrue(hasMarkdownFrontmatter("---\nname: broken"))
        assertThrows<IllegalArgumentException> { parseMarkdownFrontmatter("---\nname: broken") }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["tags: [engineering, productA]", "tags:\n  - engineering\n  - productA"]
    )
    fun `parses yaml lists in frontmatter`(metadata: String) {
        val document = parseMarkdownFrontmatter("---\n$metadata\n---\nBody.")

        assertEquals(listOf("engineering", "productA"), document.frontmatter["tags"])
        assertEquals("Body.", document.body)
    }

    @Test
    fun `parses yaml booleans and multiline descriptions`() {
        val document =
            parseMarkdownFrontmatter(
                """
                ---
                user-invocable: true
                description: |-
                  First line.
                  Second line.
                ---
                Body.
                """
                    .trimIndent()
            )

        assertEquals(true, document.frontmatter["user-invocable"])
        assertEquals("First line.\nSecond line.", document.frontmatter["description"])
    }

    @Test
    fun `empty frontmatter preserves the body`() {
        val document = parseMarkdownFrontmatter("---\n---\nBody.\n---\nMore body.")

        assertEquals(emptyMap(), document.frontmatter)
        assertEquals("Body.\n---\nMore body.", document.body)
    }

    @Test
    fun `yaml helper parses nested mappings`() {
        val metadata = parseYaml("project:\n  name: productA\n  tags: [engineering]")

        assertEquals(
            mapOf("project" to mapOf("name" to "productA", "tags" to listOf("engineering"))),
            metadata,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["tags: [unfinished", "- engineering", "123: value"])
    fun `invalid metadata is reported as an argument error`(metadata: String) {
        assertThrows<IllegalArgumentException> {
            parseMarkdownFrontmatter("---\n$metadata\n---\nBody.")
        }
    }
}
