package com.github.uncomplexco.sidekick.application.workspace

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class VirtualPathTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `resolves session virtual paths`() {
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")

        val result = parseVirtualPath("session:/attachments/file.md", sessionRoot, skillsRoot)

        assertEquals(sessionRoot.resolve("attachments/file.md").toString(), result)
    }

    @Test
    fun `resolves skills virtual paths`() {
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")

        val result = parseVirtualPath("skills:/repo/skill/SKILL.md", sessionRoot, skillsRoot)

        assertEquals(skillsRoot.resolve("repo/skill/SKILL.md").toString(), result)
    }
}
