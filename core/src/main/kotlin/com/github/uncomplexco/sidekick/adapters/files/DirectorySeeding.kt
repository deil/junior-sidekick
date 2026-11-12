package com.github.uncomplexco.sidekick.adapters.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun seedDirectory(source: Path, destination: Path) {
    Files.walk(source).use { paths ->
        paths.forEach { file ->
            val relative = source.relativize(file)
            if (relative.toString().isBlank()) {
                return@forEach
            }

            val target = destination.resolve(relative)
            if (Files.isDirectory(file)) {
                Files.createDirectories(target)
            } else if (!Files.exists(target)) {
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
