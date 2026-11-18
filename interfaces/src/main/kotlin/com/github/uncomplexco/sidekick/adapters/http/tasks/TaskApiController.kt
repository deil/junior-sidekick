package com.github.uncomplexco.sidekick.adapters.http.tasks

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.application.tools.subagents.PublicTaskRuns
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import java.net.URI
import java.security.Principal
import java.util.UUID
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

@RestController
@RequestMapping(TaskApiController.PATH)
class TaskApiController(private val runs: PublicTaskRuns) {
    data class Request(
        val prompt: String,
        @JsonProperty("subagent_type") val subagentType: String = "general",
    )

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

        val result = runs.submit(principal.name, request.subagentType, request.prompt)
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
    ): ResponseEntity<Map<String, String>> {
        val body =
            when (val status = runs.status(principal.name, runId)) {
                PublicTaskRuns.Status.Queued -> mapOf("status" to "queued")
                PublicTaskRuns.Status.Running -> mapOf("status" to "running")
                is PublicTaskRuns.Status.Completed ->
                    mapOf("status" to "completed", "result" to status.result)
                PublicTaskRuns.Status.Failed ->
                    mapOf("status" to "failed", "error" to "Task execution failed")
                null -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task run not found")
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    companion object {
        internal const val PATH = "/api/internal/tasks"
    }
}
