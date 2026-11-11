package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.PatchChange
import com.github.uncomplexco.sidekick.ports.filesystem.PatchChangeKind
import com.github.uncomplexco.sidekick.ports.filesystem.PatchResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemPatchTest {
    @TempDir lateinit var dir: Path

    @BeforeEach
    fun createWorkspace() {
        Files.createDirectory(dir.resolve("project"))
    }

    @Test
    fun `patch adds updates and deletes files in order`() = runBlocking {
        Files.writeString(dir.resolve("project/modify.txt"), "line1\nline2\n")
        Files.writeString(dir.resolve("project/delete.txt"), "obsolete\n")
        val patch =
            """*** Begin Patch
*** Add File: nested/new.txt
+created
*** Delete File: delete.txt
*** Update File: modify.txt
@@
-line2
+changed
*** End Patch"""

        val result = filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals(
            PatchResult(
                listOf(
                    PatchChange("/data/project/nested/new.txt", PatchChangeKind.ADDED),
                    PatchChange("/data/project/delete.txt", PatchChangeKind.DELETED),
                    PatchChange("/data/project/modify.txt", PatchChangeKind.MODIFIED),
                )
            ),
            result,
        )
        assertEquals("created\n", Files.readString(dir.resolve("project/nested/new.txt")))
        assertEquals("line1\nchanged\n", Files.readString(dir.resolve("project/modify.txt")))
        assertFalse(Files.exists(dir.resolve("project/delete.txt")))
    }

    @Test
    fun `patch updates and moves a file`() = runBlocking {
        Files.writeString(dir.resolve("project/source.txt"), "old\n")
        val patch =
            """*** Begin Patch
*** Update File: source.txt
*** Move to: nested/destination.txt
@@
-old
+new
*** End Patch"""

        val result = filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals(
            PatchResult(
                listOf(
                    PatchChange("/data/project/nested/destination.txt", PatchChangeKind.MODIFIED)
                )
            ),
            result,
        )
        assertEquals("new\n", Files.readString(dir.resolve("project/nested/destination.txt")))
        assertFalse(Files.exists(dir.resolve("project/source.txt")))
    }

    @Test
    fun `patch supports absolute virtual paths within its base directory`() = runBlocking {
        val patch =
            """*** Begin Patch
*** Add File: /data/project/new.txt
+created
*** End Patch"""

        filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals("created\n", Files.readString(dir.resolve("project/new.txt")))
    }

    @Test
    fun `patch resolves relative paths from the supplied base directory`() = runBlocking {
        Files.createDirectory(dir.resolve("project/repo"))
        val patch =
            """*** Begin Patch
*** Add File: new.txt
+created
*** End Patch"""

        filesystem().applyPatch("C123", "/data/project/repo", patch)

        assertEquals("created\n", Files.readString(dir.resolve("project/repo/new.txt")))
    }

    @Test
    fun `patch applies multiple hunks and adds a trailing newline`() = runBlocking {
        Files.writeString(dir.resolve("project/file.txt"), "first\nmiddle\nlast")
        val patch =
            """*** Begin Patch
*** Update File: file.txt
@@
-first
+FIRST
@@
-last
+LAST
*** End Patch"""

        filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals("FIRST\nmiddle\nLAST\n", Files.readString(dir.resolve("project/file.txt")))
    }

    @Test
    fun `patch inserts text without an old block`() = runBlocking {
        Files.writeString(dir.resolve("project/file.txt"), "first\n")
        val patch =
            """*** Begin Patch
*** Update File: file.txt
@@
+last
*** End Patch"""

        filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals("first\nlast\n", Files.readString(dir.resolve("project/file.txt")))
    }

    @Test
    fun `patch creates an empty file for an empty add section`() = runBlocking {
        val patch =
            """*** Begin Patch
*** Add File: empty.txt
*** End Patch"""

        filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals("", Files.readString(dir.resolve("project/empty.txt")))
    }

    @Test
    fun `patch normalizes crlf input`() = runBlocking {
        val patch = "*** Begin Patch\r\n*** Add File: new.txt\r\n+created\r\n*** End Patch"

        filesystem().applyPatch("C123", "/data/project", patch)

        assertEquals("created\n", Files.readString(dir.resolve("project/new.txt")))
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "",
                "invalid",
                "*** Begin Patch\n*** End Patch",
                "*** Begin Patch\n\n*** End Patch",
                "*** Begin Patch\n*** Add File: new.txt\ninvalid\n*** End Patch",
                "*** Begin Patch\n*** Update File: file.txt\n*** End Patch",
            ]
    )
    fun `patch rejects empty or malformed input`(patch: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project", patch) }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["*** Delete File: missing.txt", "*** Update File: missing.txt\n@@\n-old\n+new"]
    )
    fun `patch rejects missing source files`(operation: String) {
        val patch = "*** Begin Patch\n$operation\n*** End Patch"

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project", patch) }
        }
    }

    @Test
    fun `failed update leaves its source unchanged`() {
        Files.writeString(dir.resolve("project/file.txt"), "original\n")
        val patch =
            """*** Begin Patch
*** Update File: file.txt
@@
-missing
+new
*** End Patch"""

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project", patch) }
        }
        assertEquals("original\n", Files.readString(dir.resolve("project/file.txt")))
    }

    @Test
    fun `later failure preserves earlier applied operations`() {
        val patch =
            """*** Begin Patch
*** Add File: first.txt
+created
*** Delete File: missing.txt
*** End Patch"""

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project", patch) }
        }
        assertEquals("created\n", Files.readString(dir.resolve("project/first.txt")))
    }

    @Test
    fun `patch rejects paths escaping its base directory`() {
        Files.createDirectory(dir.resolve("project/repo"))
        val patch =
            """*** Begin Patch
*** Add File: ../outside.txt
+created
*** End Patch"""

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project/repo", patch) }
        }
        assertFalse(Files.exists(dir.resolve("project/outside.txt")))
    }

    @Test
    fun `patch rejects a move outside its base directory without changing its source`() {
        Files.createDirectory(dir.resolve("project/repo"))
        Files.writeString(dir.resolve("project/repo/file.txt"), "old\n")
        val patch =
            """*** Begin Patch
*** Update File: file.txt
*** Move to: ../outside.txt
@@
-old
+new
*** End Patch"""

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project/repo", patch) }
        }
        assertEquals("old\n", Files.readString(dir.resolve("project/repo/file.txt")))
        assertFalse(Files.exists(dir.resolve("project/outside.txt")))
    }

    @Test
    fun `patch rejects writes through a symbolic link parent`() {
        Files.createDirectory(dir.resolve("project/real"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("project/real"))
        val patch =
            """*** Begin Patch
*** Add File: linked/new.txt
+created
*** End Patch"""

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().applyPatch("C123", "/data/project", patch) }
        }
        assertFalse(Files.exists(dir.resolve("project/real/new.txt")))
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory()
            .create(
                VirtualPaths(
                    sessionRoot = dir.resolve("session"),
                    skillsRoot = dir.resolve("skills"),
                    globalRoot = dir.resolve("global"),
                    workRoot = dir.resolve("work"),
                    projectRoot = dir.resolve("project"),
                )
            )
}
