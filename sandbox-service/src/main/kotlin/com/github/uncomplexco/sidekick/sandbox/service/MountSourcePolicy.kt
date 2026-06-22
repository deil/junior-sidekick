package com.github.uncomplexco.sidekick.sandbox.service

import java.nio.file.Files
import java.nio.file.Path

class MountSourcePolicy(
    allowedPrefixes: List<Path>,
) {
    private val allowedPrefixes =
        allowedPrefixes.map {
            val prefix = it.toAbsolutePath().normalize()
            require(Files.exists(prefix)) { "Allowed source prefix does not exist: $it" }
            prefix.toRealPath()
        }

    fun validate(source: Path): Path {
        val normalized = source.toAbsolutePath().normalize()
        require(Files.exists(normalized)) { "Mount source does not exist: $source" }
        val realSource = normalized.toRealPath()
        require(allowedPrefixes.any { isInsideAllowedPrefix(realSource, it) }) { "Mount source is not allowed: $source" }
        return realSource
    }
}

internal fun isInsideAllowedPrefix(
    path: Path,
    allowedPrefix: Path,
): Boolean = path.normalize().startsWith(allowedPrefix.normalize())
