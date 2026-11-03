package com.github.uncomplexco.sidekick.application.context

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProjectContextsTest {
    @TempDir lateinit var projectRoot: Path
    private val contexts = ProjectContexts()

    @Test
    fun `missing project instructions resolve to empty context`() {
        val context = contexts.resolve(projectRoot)

        assertEquals(ProjectContext(), context)
    }

    @Test
    fun `plain markdown supplies instructions with trailing whitespace trimmed`() {
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "# Engineering\n\nUse project conventions.\n\n",
        )

        val context = contexts.resolve(projectRoot)

        assertEquals(
            ProjectContext(instructions = "# Engineering\n\nUse project conventions."),
            context,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "tags: [engineering, production, productA]",
                "tags:\n  - engineering\n  - production\n  - productA",
            ]
    )
    fun `frontmatter tags and markdown instructions resolve separately`(metadata: String) {
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "---\n$metadata\n---\n# Production engineering\n",
        )

        val context = contexts.resolve(projectRoot)

        assertEquals(
            ProjectContext(
                setOf("engineering", "production", "productA"),
                "# Production engineering",
            ),
            context,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "tags: []", "owner: support"])
    fun `optional tags default to empty while other metadata stays outside instructions`(
        metadata: String
    ) {
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "---\n$metadata\n---\nSupport instructions",
        )

        val context = contexts.resolve(projectRoot)

        assertEquals(ProjectContext(instructions = "Support instructions"), context)
    }

    @Test
    fun `duplicate tags resolve as a set`() {
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "---\ntags: [engineering, engineering]\n---\nInstructions",
        )

        val context = contexts.resolve(projectRoot)

        assertEquals(setOf("engineering"), context.tags)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tags: engineering", "tags: [engineering, 123]", "- engineering"])
    fun `invalid project metadata is reported`(metadata: String) {
        Files.writeString(projectRoot.resolve("AGENTS.md"), "---\n$metadata\n---\nInstructions")

        assertThrows<IllegalArgumentException> { contexts.resolve(projectRoot) }
    }

    @Test
    fun `unterminated frontmatter is reported`() {
        Files.writeString(
            projectRoot.resolve("AGENTS.md"),
            "---\ntags: [engineering]\nInstructions",
        )

        assertThrows<IllegalArgumentException> { contexts.resolve(projectRoot) }
    }
}
