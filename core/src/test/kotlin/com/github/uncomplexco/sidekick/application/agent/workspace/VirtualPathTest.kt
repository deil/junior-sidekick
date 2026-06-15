package com.github.uncomplexco.sidekick.application.agent.workspace

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
        val globalRoot = dir.resolve("global")

        val result = parseVirtualPath("session:/attachments/file.md", sessionRoot, skillsRoot, globalRoot)

        assertEquals(sessionRoot.resolve("attachments/file.md").toString(), result)
    }

    @Test
    fun `resolves skills virtual paths`() {
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")
        val globalRoot = dir.resolve("global")

        val result = parseVirtualPath("skills:/repo/skill/SKILL.md", sessionRoot, skillsRoot, globalRoot)

        assertEquals(skillsRoot.resolve("repo/skill/SKILL.md").toString(), result)
    }

    @Test
    fun `resolves global virtual paths`() {
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")
        val globalRoot = dir.resolve("global")

        val result = parseVirtualPath("global:/handbook/security.md", sessionRoot, skillsRoot, globalRoot)

        assertEquals(globalRoot.resolve("handbook/security.md").toString(), result)
    }
}
