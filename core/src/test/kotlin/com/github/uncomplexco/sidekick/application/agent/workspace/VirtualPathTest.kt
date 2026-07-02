package com.github.uncomplexco.sidekick.application.agent.workspace

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.test.assertEquals

class VirtualPathTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `factory stores bash work outside session folder`() {
        val stateRoot = dir.resolve("state")
        val workingRoot = dir.resolve("workspace")
        val conversationId = ConversationId("C123", "1700000000.000")

        val virtualPaths = VirtualPathsFactory(AgentConfig("Sidekick", stateRoot.toString(), workingRoot.toString())).forConversation(conversationId)

        assertEquals(workingRoot.resolve("threads/C123_1700000000.000"), virtualPaths.workRoot)
        assertEquals(stateRoot.resolve("slack/channels/C123/threads/1700000000.000/attachments"), virtualPaths.sessionRoot)
        assertEquals(workingRoot.resolve("projects/C123"), virtualPaths.projectRoot)
        assertEquals(true, Files.isDirectory(virtualPaths.sessionRoot))
    }

    @Test
    fun `factory copies work and project templates`() {
        val stateRoot = dir.resolve("state")
        val workingRoot = dir.resolve("workspace")
        Files.createDirectories(workingRoot.resolve("templates/work/bin"))
        Files.writeString(workingRoot.resolve("templates/work/bin/tool.sh"), "work\n")
        Files.createDirectories(workingRoot.resolve("templates/project/docs"))
        Files.writeString(workingRoot.resolve("templates/project/docs/readme.md"), "project\n")
        val conversationId = ConversationId("C123", "1700000000.000")

        val virtualPaths = VirtualPathsFactory(AgentConfig("Sidekick", stateRoot.toString(), workingRoot.toString())).forConversation(conversationId)

        assertEquals("work\n", Files.readString(virtualPaths.workRoot.resolve("bin/tool.sh")))
        assertEquals("project\n", Files.readString(virtualPaths.projectRoot.resolve("docs/readme.md")))
    }

    @Test
    fun `factory does not overwrite existing files with templates`() {
        val stateRoot = dir.resolve("state")
        val workingRoot = dir.resolve("workspace")
        Files.createDirectories(workingRoot.resolve("templates/project"))
        Files.writeString(workingRoot.resolve("templates/project/config.txt"), "template\n")
        Files.createDirectories(workingRoot.resolve("projects/C123"))
        Files.writeString(workingRoot.resolve("projects/C123/config.txt"), "existing\n")
        val conversationId = ConversationId("C123", "1700000000.000")

        val virtualPaths = VirtualPathsFactory(AgentConfig("Sidekick", stateRoot.toString(), workingRoot.toString())).forConversation(conversationId)

        assertEquals("existing\n", Files.readString(virtualPaths.projectRoot.resolve("config.txt")))
    }

    @ParameterizedTest
    @MethodSource("virtualToRealCases")
    fun `maps virtual paths to real paths`(
        virtualPath: String,
        expected: (Path, Path, Path, Path, Path) -> Path,
    ) {
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")
        val globalRoot = dir.resolve("global")
        val workRoot = dir.resolve("work")
        val projectRoot = dir.resolve("project")
        val virtualPaths = VirtualPaths(sessionRoot, skillsRoot, globalRoot, workRoot, projectRoot)

        val result = parseVirtualPath(virtualPath, virtualPaths)

        assertEquals(expected(sessionRoot, skillsRoot, globalRoot, workRoot, projectRoot).toString(), result)
    }

    @ParameterizedTest
    @MethodSource("realToVirtualCases")
    fun `maps real paths to virtual paths`(
        root: String,
        relativePath: String,
        expected: String,
    ) {
        val base = dir.resolve("workspace")
        val virtualPaths =
            VirtualPaths(
                sessionRoot = base.resolve("session"),
                skillsRoot = base.resolve("skills"),
                globalRoot = base.resolve("global"),
                workRoot = base.resolve("work"),
                projectRoot = base.resolve("project"),
            )
        val absolutePath = base.resolve(root).resolve(relativePath).toString()

        val result =
            when (root) {
                "session" -> virtualPaths.virtualPath(absolutePath)
                "skills" -> virtualPaths.virtualPath(absolutePath)
                "global" -> virtualPaths.virtualPath(absolutePath)
                "project" -> virtualPaths.virtualPath(absolutePath)
                else -> error("Unsupported test root: $root")
            }

        assertEquals(expected, result)
    }

    companion object {
        @JvmStatic
        fun virtualToRealCases(): Stream<Array<Any>> =
            Stream.of(
                arrayOf("/data/session", { session: Path, _: Path, _: Path, _: Path, _: Path -> session }),
                arrayOf("/data/session/file.md", { session: Path, _: Path, _: Path, _: Path, _: Path -> session.resolve("file.md") }),
                arrayOf("/data/skills", { _: Path, skills: Path, _: Path, _: Path, _: Path -> skills }),
                arrayOf("/data/skills/repo/skill/SKILL.md", { _: Path, skills: Path, _: Path, _: Path, _: Path -> skills.resolve("repo/skill/SKILL.md") }),
                arrayOf("/data/global", { _: Path, _: Path, global: Path, _: Path, _: Path -> global }),
                arrayOf("/data/global/handbook/security.md", { _: Path, _: Path, global: Path, _: Path, _: Path -> global.resolve("handbook/security.md") }),
                arrayOf("/work", { _: Path, _: Path, _: Path, work: Path, _: Path -> work }),
                arrayOf("/work/result.md", { _: Path, _: Path, _: Path, work: Path, _: Path -> work.resolve("result.md") }),
                arrayOf("/data/project", { _: Path, _: Path, _: Path, _: Path, project: Path -> project }),
                arrayOf("/data/project/src/Main.kt", { _: Path, _: Path, _: Path, _: Path, project: Path -> project.resolve("src/Main.kt") }),
            )

        @JvmStatic
        fun realToVirtualCases(): Stream<Array<Any>> =
            Stream.of(
                arrayOf("session", "", "/data/session"),
                arrayOf("session", "file.md", "/data/session/file.md"),
                arrayOf("skills", "", "/data/skills"),
                arrayOf("skills", "repo/skill/SKILL.md", "/data/skills/repo/skill/SKILL.md"),
                arrayOf("global", "", "/data/global"),
                arrayOf("global", "handbook/security.md", "/data/global/handbook/security.md"),
                arrayOf("project", "", "/data/project"),
                arrayOf("project", "src/Main.kt", "/data/project/src/Main.kt"),
            )
    }
}
