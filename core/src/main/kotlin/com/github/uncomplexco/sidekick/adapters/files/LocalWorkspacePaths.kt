package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import java.nio.file.Files
import java.nio.file.Path

internal class LocalWorkspacePaths(private val virtualPaths: VirtualPaths) {
    fun resolve(path: String): Path {
        val normalizedPath = path.trimEnd('/')
        val root =
            virtualPaths.roots.firstOrNull {
                normalizedPath == it.virtual || normalizedPath.startsWith("${it.virtual}/")
            } ?: throw IllegalArgumentException("Path not found: $path")
        val realRoot = root.real.toAbsolutePath().normalize()
        val target =
            realRoot.resolve(normalizedPath.removePrefix(root.virtual).trimStart('/')).normalize()
        require(target.startsWith(realRoot)) { "Path escapes working directory: $path" }

        var current = realRoot
        require(!Files.isSymbolicLink(current)) {
            "Symbolic links are not allowed in workspace paths: $current"
        }
        for (segment in realRoot.relativize(target)) {
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "Symbolic links are not allowed in workspace paths: $current"
            }
        }

        return target
    }
}
