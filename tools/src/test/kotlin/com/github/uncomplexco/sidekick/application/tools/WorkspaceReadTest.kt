package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.adapters.files.WorkspaceFilesystemFactory
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceRead
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WorkspaceReadTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `read paginates file contents in opencode format`() {
        Files.writeString(dir.resolve("notes.txt"), "one\ntwo\nthree\n")

        val result = workspaceRead().read("/data/session/notes.txt", offset = 2, limit = 2)

        assertContains(result, "<path>/data/session/notes.txt</path>")
        assertContains(result, "<type>file</type>")
        assertContains(result, "2: two")
        assertContains(result, "3: three")
        assertContains(result, "(End of file - total 3 lines)")
    }

    @Test
    fun `read directory returns opencode style entries`() {
        Files.createDirectories(dir.resolve("src/nested"))
        Files.writeString(dir.resolve("src/App.kt"), "class App")

        val result = workspaceRead().read("/data/session/src", offset = 1, limit = 10)

        assertContains(result, "<path>/data/session/src</path>")
        assertContains(result, "<type>directory</type>")
        assertContains(result, "App.kt")
        assertContains(result, "nested/")
        assertContains(result, "(2 entries)")
    }

    @Test
    fun `read clips long lines in tool output`() {
        Files.writeString(dir.resolve("notes.txt"), "x".repeat(3000) + "\n")

        val result = workspaceRead().read("/data/session/notes.txt", offset = 1, limit = 10)

        assertContains(result, "1: ${"x".repeat(2000)}... (line truncated to 2000 chars)")
        assertContains(result, "End of file - total 1 lines")
    }

    @Test
    fun `read reports the backend byte cap`() {
        Files.writeString(dir.resolve("notes.txt"), ("x".repeat(1000) + "\n").repeat(100))

        val result = workspaceRead().read("/data/session/notes.txt", offset = 1, limit = 2000)

        assertContains(result, "Output capped at 50 KB")
    }

    @Test
    fun `read reports remaining lines after line limit`() {
        Files.writeString(dir.resolve("notes.txt"), "one\ntwo\nthree\n")

        val result = workspaceRead().read("/data/session/notes.txt", offset = 1, limit = 1)

        assertContains(result, "Showing lines 1-1 of 3. Use offset=2 to continue.")
    }

    @Test
    fun `rejects paths outside the configured root`() {
        val files = workspaceRead()

        assertThrows<IllegalArgumentException> {
            files.read("/data/session/../secret.txt", offset = 1, limit = 10)
        }
    }

    @Test
    fun `rejects symlinked files`() {
        Files.writeString(dir.resolve("target.txt"), "secret\n")
        Files.createSymbolicLink(dir.resolve("linked.txt"), dir.resolve("target.txt"))

        val error =
            assertThrows<IllegalArgumentException> {
                workspaceRead().read("/data/session/linked.txt", offset = 1, limit = 10)
            }

        assertContains(error.message.orEmpty(), "Symbolic links are not allowed")
    }

    private fun workspaceRead(): WorkspaceRead {
        val paths =
            VirtualPaths(
                sessionRoot = dir,
                skillsRoot = dir.resolve("skills"),
                globalRoot = dir.resolve("global"),
                workRoot = dir.resolve("work"),
                projectRoot = dir.resolve("project"),
            )

        return WorkspaceRead("C123", WorkspaceFilesystemFactory().create(paths))
    }
}
