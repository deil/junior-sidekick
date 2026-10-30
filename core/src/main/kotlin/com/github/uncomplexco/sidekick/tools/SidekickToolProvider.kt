package com.github.uncomplexco.sidekick.tools

import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import java.nio.file.Path

fun interface SidekickToolProvider {
    fun registrations(): List<ToolRegistration<SidekickToolContext>>
}

fun interface SidekickToolContext {
    fun resolvePath(virtualPath: String): Path
}
