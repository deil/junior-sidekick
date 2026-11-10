package com.github.uncomplexco.sidekick.sandbox.service

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val PROJECT_ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
private const val MAX_TIMEOUT_SECONDS = 2_147_483L

fun Application.projectExecuteRoute(token: String, executor: ProjectCommandExecutor) {
    routing {
        post("/api/v2/execute") {
            if (call.request.headers["Authorization"] != "Bearer $token") {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                return@post
            }

            val request = call.receive<ProjectExecuteRequest>()
            val error =
                when {
                    !PROJECT_ID_RE.matches(request.projectId) -> "Invalid projectId"
                    request.command.isBlank() || '\u0000' in request.command ->
                        "command must be nonblank and contain no NUL bytes"
                    request.timeoutSeconds !in 1..MAX_TIMEOUT_SECONDS ->
                        "timeoutSeconds must be between 1 and $MAX_TIMEOUT_SECONDS"
                    request.workdir != null &&
                        (request.workdir.isBlank() || '\u0000' in request.workdir) ->
                        "workdir must be nonblank and contain no NUL bytes"
                    else -> null
                }
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@post
            }

            val result = withContext(Dispatchers.IO) { executor.execute(request) }
            call.respond(Json.encodeToJsonElement(result))
        }
    }
}

@Serializable
data class ProjectExecuteRequest(
    val projectId: String,
    val command: String,
    val workdir: String? = null,
    val timeoutSeconds: Long,
)
