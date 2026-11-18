package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.tools.subagents.IsolatedSubagentRunner
import com.github.uncomplexco.sidekick.application.tools.subagents.Subagent
import kotlinx.coroutines.CompletableDeferred

class TaskApiTestRunner : IsolatedSubagentRunner {
    val result = CompletableDeferred<AgentRunResult<String>>()
    val requests = mutableListOf<Pair<String, String>>()
    val executionIds = mutableListOf<String>()

    override suspend fun runIsolated(
        runId: String,
        subagent: Subagent,
        prompt: String,
    ): AgentRunResult<String> {
        requests += subagent.name to prompt
        executionIds += runId
        return result.await()
    }
}
