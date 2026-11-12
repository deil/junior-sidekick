package com.github.uncomplexco.sidekick.adapters.files

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.PathMatcher

internal fun grepCandidates(search: Path, cwd: Path): List<Path> {
    if (Files.isRegularFile(search, NOFOLLOW_LINKS)) return listOf(search)

    return Files.walk(cwd).use { files ->
        files
            .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .filter { file -> file.none { it.toString() == ".git" } }
            .toList()
    }
}

internal fun globMatchers(base: Path, pattern: String): List<PathMatcher> {
    val patterns = linkedSetOf(pattern)
    if (pattern.startsWith("**/")) patterns += pattern.removePrefix("**/")

    return patterns.map { base.fileSystem.getPathMatcher("glob:$it") }
}
