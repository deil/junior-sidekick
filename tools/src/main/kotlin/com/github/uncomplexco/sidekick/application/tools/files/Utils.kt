package com.github.uncomplexco.sidekick.application.tools.files

import com.github.uncomplexco.sidekick.application.core.VirtualPath
import java.nio.file.Path

fun parseVirtualPath(
    path: VirtualPath,
    sessionRoot: Path,
): String {
    if (path.startsWith("session:/")) {
        return sessionRoot.resolve(path.removePrefix("session:/")).toString()
    }

    return path
}
