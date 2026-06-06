package com.github.uncomplexco.sidekick.application.workspace

import java.nio.file.Path

typealias AbsolutePath = String
typealias VirtualPath = String

fun sessionPath(
    sessionRoot: Path,
    path: AbsolutePath,
): VirtualPath = sessionRoot.relativize(Path.of(path)).toString().toSessionBasedPath()

fun String.toSessionBasedPath(): VirtualPath = "session:/$this"

fun parseVirtualPath(
    path: VirtualPath,
    sessionRoot: Path,
): String {
    if (path.startsWith("session:/")) {
        return sessionRoot.resolve(path.removePrefix("session:/")).toString()
    }

    return path
}
