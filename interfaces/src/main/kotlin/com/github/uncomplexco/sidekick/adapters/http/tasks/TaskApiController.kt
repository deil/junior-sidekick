package com.github.uncomplexco.sidekick.adapters.http.tasks

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.application.tools.subagents.PublicTaskRuns
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskOutputSchema
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import java.net.URI
import java.security.Principal
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@RestController
@ConditionalOnProperty(prefix = "task-api", name = ["enabled"], havingValue = "true")
@RequestMapping(TaskApiController.PATH)
class TaskApiController(private val runs: PublicTaskRuns) {
    data class Request(
        val prompt: String,
        @JsonProperty("subagent_type") val subagentType: String = "general",
        @JsonProperty("output_schema") val outputSchema: JsonNode? = null,
    )

    private val jsonMapper = JsonMapper.builder().build()

    @PostMapping
    fun submit(
        @RequestBody request: Request,
        principal: Principal,
    ): ResponseEntity<Map<String, String>> {
        if (request.prompt.isBlank() || request.prompt.length > TaskTool.TASK_PROMPT_MAX_CHARS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Prompt must contain 1 to ${TaskTool.TASK_PROMPT_MAX_CHARS} characters and cannot be blank",
            )
        }

        val schema = request.outputSchema?.let(::parseSchema)
        val result = runs.submit(principal.name, request.subagentType, request.prompt, schema)
        return when (result) {
            is PublicTaskRuns.Submission.Accepted ->
                ResponseEntity.accepted()
                    .location(URI.create("$PATH/${result.id}"))
                    .cacheControl(CacheControl.noStore())
                    .body(mapOf("run_id" to result.id.toString()))
            PublicTaskRuns.Submission.UnknownSubagent ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subagent type")
            PublicTaskRuns.Submission.AtCapacity ->
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Task API is at capacity",
                )
        }
    }

    @GetMapping("/{runId}")
    fun poll(
        @PathVariable("runId") runId: UUID,
        principal: Principal,
    ): ResponseEntity<Map<String, Any>> {
        val body =
            when (val status = runs.status(principal.name, runId)) {
                PublicTaskRuns.Status.Queued -> mapOf("status" to "queued")
                PublicTaskRuns.Status.Running -> mapOf("status" to "running")
                is PublicTaskRuns.Status.Completed ->
                    mapOf("status" to "completed", "result" to status.result)
                is PublicTaskRuns.Status.Structured ->
                    mapOf(
                        "status" to "completed",
                        "result" to jsonMapper.readTree(status.result.toString()),
                    )
                PublicTaskRuns.Status.InvalidOutput ->
                    mapOf(
                        "status" to "failed",
                        "error" to "Task output did not match the schema",
                        "error_code" to "invalid_output",
                    )
                PublicTaskRuns.Status.Failed ->
                    mapOf("status" to "failed", "error" to "Task execution failed")
                null -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task run not found")
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    private fun parseSchema(input: JsonNode): TaskOutputSchema =
        try {
            val text = input.toString()
            require(text.length <= 16_384) { "output_schema exceeds 16384 characters" }
            val schema =
                Json.parseToJsonElement(text) as? JsonObject
                    ?: throw IllegalArgumentException("output_schema must be a JSON object")
            TaskOutputSchema(schema)
        } catch (error: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        }

    companion object {
        internal const val PATH = "/api/internal/tasks"
    }
}
