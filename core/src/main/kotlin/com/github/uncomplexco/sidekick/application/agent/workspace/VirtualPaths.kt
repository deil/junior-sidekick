package com.github.uncomplexco.sidekick.application.agent.workspace

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.onProjectCreated
import com.github.uncomplexco.sidekick.application.agent.onWorkCreated
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

typealias AbsolutePath = String
typealias VirtualPath = String

data class VirtualRoot(
    val virtual: VirtualPath,
    val real: Path,
    val writable: Boolean,
) {
    fun contains(path: Path): Boolean = path == normalizedReal() || path.startsWith(normalizedReal())

    fun virtualPath(path: Path): VirtualPath {
        val relative = normalizedReal().relativize(path)
        return Path.of(virtual).resolve(relative).toString()
    }

    private fun normalizedReal(): Path = real.toAbsolutePath().normalize()
}

data class VirtualPaths(
    val sessionRoot: Path,
    val skillsRoot: Path,
    val globalRoot: Path,
    val workRoot: Path,
    val projectRoot: Path,
) {
    val roots: List<VirtualRoot> =
        listOf(
            VirtualRoot(SESSION_ROOT, sessionRoot, writable = false),
            VirtualRoot(SKILLS_ROOT, skillsRoot, writable = false),
            VirtualRoot(GLOBAL_ROOT, globalRoot, writable = false),
            VirtualRoot(WORK_ROOT, workRoot, writable = true),
            VirtualRoot(PROJECT_ROOT, projectRoot, writable = true),
        )

    fun virtualPath(path: AbsolutePath): VirtualPath {
        val realPath = Path.of(path).toAbsolutePath().normalize()
        return roots.firstOrNull { it.contains(realPath) }?.virtualPath(realPath)
            ?: error("Path is outside virtual roots: $path")
    }

    companion object {
        const val DATA_ROOT = "/data"
        const val SESSION_ROOT = "$DATA_ROOT/session"
        const val SKILLS_ROOT = "$DATA_ROOT/skills"
        const val GLOBAL_ROOT = "$DATA_ROOT/global"
        const val WORK_ROOT = "/work"
        const val PROJECT_ROOT = "$DATA_ROOT/project"
    }
}

@Component
class VirtualPathsFactory(
    private val config: AgentConfig,
) {
    fun forConversation(conversationId: ConversationId): VirtualPaths {
        val attachmentsRoot = Files.createDirectories(conversationId.folder(config.stateDirectoryPath()).resolve("attachments"))

        val workRoot =
            config
                .workspaceLayout()
                .sessionWorkspacesDirectoryPath()
                .resolve(sanitizePathSegment(conversationId.lockKey()))
        Files.createDirectories(workRoot)
        onWorkCreated(config, workRoot)
        val projectRoot =
            Files.createDirectories(
                config
                    .workspaceLayout()
                    .projectWorkspacesDirectoryPath()
                    .resolve(sanitizePathSegment(conversationId.channelId)),
            )
        onProjectCreated(config, projectRoot)

        return VirtualPaths(
            sessionRoot = attachmentsRoot,
            skillsRoot = config.workspaceLayout().extensionsRepositoryDirectoryPath(),
            globalRoot = config.workspaceLayout().knowledgeRepositoryDirectoryPath(),
            workRoot = workRoot,
            projectRoot = projectRoot,
        )
    }
}

fun parseVirtualPath(
    path: VirtualPath,
    virtualPaths: VirtualPaths,
): String {
    virtualPaths.roots.firstOrNull { path == it.virtual || path.startsWith("${it.virtual}/") }?.let {
        return it.real.resolve(path.removePrefix(it.virtual).trimStart('/')).toString()
    }

    error("Unknown virtual path: $path")
}
