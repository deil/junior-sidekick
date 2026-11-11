package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.PROJECT_ROOT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

internal class LocalGitPaths(private val virtualPaths: VirtualPaths) {
    fun writablePath(path: String): Path {
        val root =
            virtualPaths.roots.firstOrNull {
                path == it.virtual || path.startsWith("${it.virtual}/")
            } ?: throw IllegalArgumentException("Path must be under a writable workspace root")
        require(root.writable) { "Path must be under a writable workspace root" }

        val relative = path.removePrefix(root.virtual).trimStart('/')
        require(relative.isNotBlank()) { "Path must name a destination folder" }

        val rootPath = root.real.toAbsolutePath().normalize()
        val checkout = root.real.resolve(relative).toAbsolutePath().normalize()
        require(checkout.startsWith(rootPath)) { "Path must be under a writable workspace root" }

        return checkout
    }

    fun projectPath(path: String): Path {
        require(path == PROJECT_ROOT || path.startsWith("$PROJECT_ROOT/")) {
            "Path must be under $PROJECT_ROOT"
        }

        val relative = path.removePrefix(PROJECT_ROOT).trimStart('/')
        val checkout = virtualPaths.projectRoot.resolve(relative).toAbsolutePath().normalize()
        val projectRoot = virtualPaths.projectRoot.toAbsolutePath().normalize()
        require(checkout != projectRoot && checkout.startsWith(projectRoot)) {
            "Path must be under $PROJECT_ROOT"
        }

        return checkout
    }

    fun prepareDestination(checkout: Path) {
        val parent =
            checkout.parent ?: throw IllegalArgumentException("Path must name a destination folder")
        val root =
            virtualPaths.roots.firstOrNull { it.writable && it.contains(parent) }
                ?: throw IllegalArgumentException("Path must be under a writable workspace root")
        val rootPath = root.real.toAbsolutePath().normalize()

        var current = rootPath
        for (segment in rootPath.relativize(parent)) {
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "Path must not traverse a symbolic link: ${virtualPaths.virtualPath(current.pathString)}"
            }
        }

        Files.createDirectories(parent)
    }
}
