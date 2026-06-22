package com.github.uncomplexco.sidekick.sandbox.bwrap

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BwrapSandboxTest {
    @Test
    fun `execute passes normalized request to bwrap process`() {
        // Arrange
        val temp = Files.createTempDirectory("bwrap-sandbox-test")
        val fakeBwrap = fakeBwrap(temp, "printf '%s\\n' \"\$@\"")
        val rootfs = Files.createDirectory(temp.resolve("rootfs"))
        val scratch = Files.createDirectory(temp.resolve("scratch"))
        val sandbox =
            BwrapSandbox(
                BwrapSandboxConfig(
                    bwrapPath = fakeBwrap.pathString,
                    rootfs = rootfs,
                    maxOutputBytes = 10_000,
                    uid = 123,
                    gid = 456,
                ),
            )

        // Act
        val result =
            sandbox.execute(
                BwrapSandboxRequest(
                    command = "pwd",
                    workdir = "tmp",
                    timeoutSeconds = 5,
                    networkEnabled = false,
                    mounts = listOf(BwrapMount(scratch, "/work", BwrapMountMode.RW)),
                ),
            )

        // Assert
        assertEquals(true, result.ok)
        assertEquals(0, result.exitCode)
        assertEquals("/tmp", result.workdir)
        assertTrue(result.output.lines().contains("--unshare-net"), result.output)
        assertTrue(result.output.lines().contains("--bind"), result.output)
        assertTrue(result.output.lines().contains(scratch.pathString), result.output)
        assertTrue(result.output.lines().contains("/work"), result.output)
        assertTrue(result.output.lines().contains("pwd"), result.output)
    }

    @Test
    fun `execute caps process output`() {
        // Arrange
        val temp = Files.createTempDirectory("bwrap-sandbox-test")
        val fakeBwrap = fakeBwrap(temp, "printf 'abcdef'")
        val rootfs = Files.createDirectory(temp.resolve("rootfs"))
        val sandbox =
            BwrapSandbox(
                BwrapSandboxConfig(
                    bwrapPath = fakeBwrap.pathString,
                    rootfs = rootfs,
                    maxOutputBytes = 3,
                    uid = 123,
                    gid = 456,
                ),
            )

        // Act
        val result =
            sandbox.execute(
                BwrapSandboxRequest(
                    command = "ignored",
                    workdir = "/",
                    timeoutSeconds = 5,
                    networkEnabled = true,
                    mounts = emptyList(),
                ),
            )

        // Assert
        assertEquals("abc", result.output)
        assertEquals(true, result.outputTruncated)
    }

    @Test
    fun `execute omits network namespace isolation when network is enabled`() {
        // Arrange
        val temp = Files.createTempDirectory("bwrap-sandbox-test")
        val fakeBwrap = fakeBwrap(temp, "printf '%s\\n' \"\$@\"")
        val rootfs = Files.createDirectory(temp.resolve("rootfs"))
        val sandbox =
            BwrapSandbox(
                BwrapSandboxConfig(
                    bwrapPath = fakeBwrap.pathString,
                    rootfs = rootfs,
                    maxOutputBytes = 10_000,
                    uid = 123,
                    gid = 456,
                ),
            )

        // Act
        val result =
            sandbox.execute(
                BwrapSandboxRequest(
                    command = "pwd",
                    workdir = "/",
                    timeoutSeconds = 5,
                    networkEnabled = true,
                    mounts = emptyList(),
                ),
            )

        // Assert
        assertTrue("--unshare-net" !in result.output.lines(), result.output)
    }

    private fun fakeBwrap(
        directory: java.nio.file.Path,
        body: String,
    ): java.nio.file.Path {
        val script = directory.resolve("fake-bwrap")
        Files.writeString(
            script,
            """
            #!/usr/bin/env bash
            $body
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        return script
    }
}
