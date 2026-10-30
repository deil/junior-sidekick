package com.github.uncomplexco.sidekick.dumphere

import com.github.uncomplexco.sidekick.application.agent.tools.AgentToolRole
import com.github.uncomplexco.sidekick.application.agent.tools.ToolAccess
import com.github.uncomplexco.sidekick.tools.SidekickToolContext
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import org.junit.jupiter.api.Test

class DumpHereToolProviderTest {
    @Test
    fun `published artifacts are readable by delegates but publishing and editing are orchestrator-only`() {
        val registrations =
            DumpHereToolProvider("http://localhost:1", "unused", "unused").registrations()
        val context = SidekickToolContext { Path.of(it) }

        val sharedNames =
            registrations
                .filter { it.access == ToolAccess.SHARED }
                .flatMap { it.bind(context) }
                .map { it.name }
                .toSet()
        val orchestratorNames =
            registrations
                .filter { it.access == ToolAccess.ORCHESTRATOR_ONLY }
                .flatMap { it.bind(context) }
                .map { it.name }
                .toSet()

        assertEquals(setOf("readInternalSnippet"), sharedNames)
        assertEquals(
            setOf("publishFileInternally", "publishSnippetInternally", "editInternalSnippet"),
            orchestratorNames,
        )
        for (role in listOf(AgentToolRole.TASK, AgentToolRole.LOOP)) {
            assertEquals(
                sharedNames,
                registrations
                    .filter { it.access.permits(role) }
                    .flatMap { it.bind(context) }
                    .map { it.name }
                    .toSet(),
            )
        }
        val shared = registrations.single { it.access == ToolAccess.SHARED }
        assertNotSame(shared.bind(context).single(), shared.bind(context).single())
    }
}
