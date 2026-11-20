package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.tools.subagents.IsolatedSubagentRunner
import com.github.uncomplexco.sidekick.application.tools.subagents.Subagent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

class TaskApiTestRunner : IsolatedSubagentRunner {
    val result = CompletableDeferred<AgentRunResult<String>>()
    val requests = mutableListOf<Pair<String, String>>()
    val executionIds = mutableListOf<String>()
    val schemas = mutableListOf<JsonObject?>()

    override suspend fun runIsolated(
        runId: String,
        subagent: Subagent,
        prompt: String,
        outputSchema: JsonObject?,
    ): AgentRunResult<String> {
        requests += subagent.name to prompt
        executionIds += runId
        schemas += outputSchema
        return result.await()
    }
}
