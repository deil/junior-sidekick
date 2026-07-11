package com.github.uncomplexco.sidekick.application.markdown

import com.github.uncomplexco.sidekick.application.utils.hasMarkdownFrontmatter
import com.github.uncomplexco.sidekick.application.utils.parseMarkdownFrontmatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                """.trimIndent(),
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
}
