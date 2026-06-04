package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceFilesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `read paginates file contents in opencode format`() {
        Files.writeString(dir.resolve("notes.txt"), "one\ntwo\nthree\n")

        val result = WorkspaceFiles(dir).read("notes.txt", offset = 2, limit = 2)

        assertContains(result, "<path>${dir.resolve("notes.txt")}</path>")
        assertContains(result, "<type>file</type>")
        assertContains(result, "2: two")
        assertContains(result, "3: three")
        assertContains(result, "(End of file - total 3 lines)")
    }

    @Test
    fun `read directory returns opencode style entries`() {
        Files.createDirectories(dir.resolve("src/nested"))
        Files.writeString(dir.resolve("src/App.kt"), "class App")

        val result = WorkspaceFiles(dir).read("src", offset = 1, limit = 10)

        assertContains(result, "<path>${dir.resolve("src")}</path>")
        assertContains(result, "<type>directory</type>")
        assertContains(result, "App.kt")
        assertContains(result, "nested/")
        assertContains(result, "(2 entries)")
    }

    @Test
    fun `glob returns workspace relative matches`() {
        Files.createDirectories(dir.resolve("src/main/kotlin"))
        Files.writeString(dir.resolve("src/main/kotlin/App.kt"), "class App")
        Files.writeString(dir.resolve("src/main/kotlin/Util.kt"), "class Util")

        val result = WorkspaceFiles(dir).glob("**/*.kt", "src")

        assertContains(result, "main/kotlin/App.kt")
        assertContains(result, "main/kotlin/Util.kt")
    }

    @Test
    fun `glob follows symlinked directories`() {
        val real = Files.createDirectories(dir.resolve("real/nested"))
        Files.writeString(real.resolve("Link.kt"), "class Link")
        Files.createSymbolicLink(dir.resolve("linked"), dir.resolve("real"))

        val result = WorkspaceFiles(dir).glob("**/*.kt", "linked")

        assertContains(result, "nested/Link.kt")
    }

    @Test
    fun `grep returns grouped absolute file matches`() {
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/App.kt"), "fun main() = println(\"needle\")\n")
        Files.writeString(dir.resolve("src/App.java"), "class App {}\n")

        val result = WorkspaceFiles(dir).grep("needle", ".", "**/*.kt")

        assertContains(result, "Found 1 matches")
        assertContains(result, "${dir.resolve("src/App.kt")}:")
        assertContains(result, "Line 1: fun main() = println(\"needle\")")
    }

    @Test
    fun `grep follows symlinked directories`() {
        val real = Files.createDirectories(dir.resolve("real-src"))
        Files.writeString(real.resolve("Needle.kt"), "val x = \"needle\"\n")
        Files.createSymbolicLink(dir.resolve("linked-src"), dir.resolve("real-src"))

        val result = WorkspaceFiles(dir).grep("needle", "linked-src", "**/*.kt")

        assertContains(result, "Found 1 matches")
        assertContains(result, "Line 1: val x = \"needle\"")
    }

    @Test
    fun `grep returns no files found when there are no matches`() {
        Files.writeString(dir.resolve("empty.txt"), "hello\n")

        assertEquals("No files found", WorkspaceFiles(dir).grep("needle", null, null))
    }

    @Test
    fun `grep skips non utf8 files instead of failing`() {
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/App.kt"), "fun main() = println(\"needle\")\n")
        Files.write(dir.resolve("src/binary.dat"), byteArrayOf(0x48, 0x65, 0x79, 0x2D, 0x80.toByte()))

        val result = WorkspaceFiles(dir).grep("needle", "src", null)

        assertContains(result, "Found 1 matches")
        assertContains(result, "${dir.resolve("src/App.kt")}:")
        assertContains(result, "Line 1: fun main() = println(\"needle\")")
    }

    @Test
    fun `write matches opencode success contract`() {
        val result = WorkspaceFiles(dir).write(dir.resolve("newfile.txt").toString(), "Hello, World!")

        assertEquals("Wrote file successfully.", result)
        assertEquals("Hello, World!", Files.readString(dir.resolve("newfile.txt")))
    }

    @Test
    fun `edit matches opencode success contract`() {
        Files.writeString(dir.resolve("file.txt"), "old content here")

        val result = WorkspaceFiles(dir).edit(dir.resolve("file.txt").toString(), "old content", "new content", false)

        assertEquals("Edit applied successfully.", result)
        assertEquals("new content here", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit rejects identical old and new strings like opencode`() {
        val error =
            assertThrows<IllegalArgumentException> {
                WorkspaceFiles(dir).edit(dir.resolve("file.txt").toString(), "same", "same", false)
            }

        assertContains(error.message.orEmpty(), "No changes to apply: oldString and newString are identical.")
    }

    @Test
    fun `apply patch matches opencode success summary`() {
        Files.writeString(dir.resolve("modify.txt"), "line1\nline2\n")
        Files.writeString(dir.resolve("delete.txt"), "obsolete\n")

        val patchText =
            """*** Begin Patch
*** Add File: nested/new.txt
+created
*** Delete File: delete.txt
*** Update File: modify.txt
@@
-line2
+changed
*** End Patch"""

        val result = WorkspaceFiles(dir).applyPatch(patchText)

        assertContains(result, "Success. Updated the following files:")
        assertContains(result, "A nested/new.txt")
        assertContains(result, "D delete.txt")
        assertContains(result, "M modify.txt")
        assertEquals("created\n", Files.readString(dir.resolve("nested/new.txt")))
        assertEquals("line1\nchanged\n", Files.readString(dir.resolve("modify.txt")))
        assertTrue(Files.notExists(dir.resolve("delete.txt")))
    }

    @Test
    fun `apply patch rejects empty patch like opencode`() {
        val error =
            assertThrows<IllegalArgumentException> {
                WorkspaceFiles(dir).applyPatch("*** Begin Patch\n*** End Patch")
            }

        assertContains(error.message.orEmpty(), "patch rejected: empty patch")
    }

    @Test
    fun `rejects paths outside the configured root`() {
        val files = WorkspaceFiles(dir)

        assertThrows<IllegalArgumentException> {
            files.read("../secret.txt", offset = 1, limit = 10)
        }
    }
}
