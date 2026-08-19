package com.github.uncomplexco.sidekick.dumphere

import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.tools.SidekickToolContext
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider

class DumpHereToolProvider(
    baseUrl: String,
    username: String,
    password: String,
) : SidekickToolProvider {
    private val publisher = DumpHereFilePublisher(baseUrl, username, password)

    override fun toolSet(context: SidekickToolContext): ToolSet =
        InternalFileExchangeTools(publisher) { path -> context.resolvePath(path) }
}
