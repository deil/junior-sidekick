package com.github.uncomplexco.sidekick.application.agent.workspace

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import org.springframework.stereotype.Component
import java.nio.file.Path

typealias AbsolutePath = String
typealias VirtualPath = String

data class VirtualPaths(
    val sessionRoot: Path,
    val skillsRoot: Path,
    val globalRoot: Path,
    val workRoot: Path,
) {
    fun sessionPath(path: AbsolutePath): VirtualPath = virtualPath(sessionRoot, path, SESSION_ROOT)

    fun skillsPath(path: AbsolutePath): VirtualPath = virtualPath(skillsRoot, path, SKILLS_ROOT)

    fun globalPath(path: AbsolutePath): VirtualPath = virtualPath(globalRoot, path, GLOBAL_ROOT)

    companion object {
        const val SESSION_ROOT = "/data/session"
        const val SKILLS_ROOT = "/data/skills"
        const val GLOBAL_ROOT = "/data/global"
        const val WORK_ROOT = "/work"
    }
}

@Component
class VirtualPathsFactory(
    private val config: AgentConfig,
) {
    fun forConversation(conversationId: ConversationId): VirtualPaths {
        val sessionFolder = conversationId.folder(config.stateDirectoryPath())
        return VirtualPaths(
            sessionRoot = sessionFolder.resolve("attachments"),
            skillsRoot = config.skillsDirectoryPath(),
            globalRoot = config.globalDirectoryPath(),
            workRoot = config.stateDirectoryPath().resolve("bash").resolve(sanitizePathSegment(conversationId.lockKey())).resolve("work"),
        )
    }
}

fun skillsPath(
    skillsRoot: Path,
    path: AbsolutePath,
): VirtualPath = virtualPath(skillsRoot, path, VirtualPaths.SKILLS_ROOT)

fun parseVirtualPath(
    path: VirtualPath,
    roots: VirtualPaths,
): String {
    if (path == VirtualPaths.SESSION_ROOT || path.startsWith("${VirtualPaths.SESSION_ROOT}/")) {
        return realPath(roots.sessionRoot, path, VirtualPaths.SESSION_ROOT)
    }

    if (path == VirtualPaths.SKILLS_ROOT || path.startsWith("${VirtualPaths.SKILLS_ROOT}/")) {
        return realPath(roots.skillsRoot, path, VirtualPaths.SKILLS_ROOT)
    }

    if (path == VirtualPaths.GLOBAL_ROOT || path.startsWith("${VirtualPaths.GLOBAL_ROOT}/")) {
        return realPath(roots.globalRoot, path, VirtualPaths.GLOBAL_ROOT)
    }

    if (path == VirtualPaths.WORK_ROOT || path.startsWith("${VirtualPaths.WORK_ROOT}/")) {
        return realPath(roots.workRoot, path, VirtualPaths.WORK_ROOT)
    }

    error("Unsupported virtual path root: $path")
}

private fun virtualPath(
    realRoot: Path,
    path: AbsolutePath,
    virtualRoot: String,
): VirtualPath {
    val relative = realRoot.relativize(Path.of(path)).toString().replace('\\', '/')
    return if (relative.isBlank()) virtualRoot else "$virtualRoot/$relative"
}

private fun realPath(
    realRoot: Path,
    path: VirtualPath,
    virtualRoot: String,
): AbsolutePath = realRoot.resolve(path.removePrefix(virtualRoot).trimStart('/')).toString()
