package com.github.uncomplexco.sidekick.dumphere

import com.github.uncomplexco.sidekick.application.agent.tools.ToolAccess
import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import com.github.uncomplexco.sidekick.tools.SidekickToolContext
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider

class DumpHereToolProvider(
    baseUrl: String,
    username: String,
    password: String,
) : SidekickToolProvider {
    private val publisher = DumpHereFilePublisher(baseUrl, username, password)

    override fun registrations(): List<ToolRegistration<SidekickToolContext>> =
        listOf(
            ToolRegistration("internal-file-read", ToolAccess.SHARED) {
                InternalFileReadTools(publisher).asTools()
            },
            ToolRegistration("internal-file-publish", ToolAccess.ORCHESTRATOR_ONLY) { context ->
                InternalFileExchangeTools(publisher) { path -> context.resolvePath(path) }.asTools()
            },
        )
}
