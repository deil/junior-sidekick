package com.github.uncomplexco.sidekick.tools

import ai.koog.agents.core.tools.reflect.ToolSet
import java.nio.file.Path

fun interface SidekickToolProvider {
    fun toolSet(context: SidekickToolContext): ToolSet
}

fun interface SidekickToolContext {
    fun resolvePath(virtualPath: String): Path
}
