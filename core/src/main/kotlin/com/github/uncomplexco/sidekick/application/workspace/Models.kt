package com.github.uncomplexco.sidekick.application.workspace

import java.nio.file.Path

typealias AbsolutePath = String
typealias VirtualPath = String

fun sessionPath(
    sessionRoot: Path,
    path: AbsolutePath,
): VirtualPath = sessionRoot.relativize(Path.of(path)).toString().toSessionBasedPath()

fun String.toSessionBasedPath(): VirtualPath = "session:/$this"

fun skillsPath(
    skillsRoot: Path,
    path: AbsolutePath,
): VirtualPath = skillsRoot.relativize(Path.of(path)).toString().toSkillsBasedPath()

fun String.toSkillsBasedPath(): VirtualPath = "skills:/$this"

fun parseVirtualPath(
    path: VirtualPath,
    sessionRoot: Path,
    skillsRoot: Path,
): String {
    if (path.startsWith("session:/")) {
        return sessionRoot.resolve(path.removePrefix("session:/")).toString()
    }

    if (path.startsWith("skills:/")) {
        return skillsRoot.resolve(path.removePrefix("skills:/")).toString()
    }

    error("Unsupported virtual path root: $path")
}
