package com.github.uncomplexco.sidekick.sandbox.service

import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapMount
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapMountMode
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxRequest
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.nio.file.Path

fun interface SandboxCommandExecutor {
    fun execute(request: BwrapSandboxRequest): BwrapSandboxResult
}

fun Application.executeRoute(
    token: String,
    mountSourcePolicy: MountSourcePolicy,
    executor: SandboxCommandExecutor,
) {
    routing {
        post("/api/execute") {
            if (call.request.headers["Authorization"] != "Bearer $token") {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                return@post
            }

            val request = call.receive<ExecuteRequest>()
            val sandboxRequest =
                try {
                    request.toSandboxRequest(mountSourcePolicy)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid execute request"))
                    return@post
                }

            val result =
                try {
                    executor.execute(sandboxRequest)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid execute request"))
                    return@post
                }

            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class ExecuteRequest(
    val command: String,
    val workdir: String = "/",
    val timeoutSeconds: Long,
    val networkEnabled: Boolean,
    val mounts: List<MountRequest> = emptyList(),
)

@Serializable
data class MountRequest(
    val source: String,
    val target: String,
    val mode: String,
)

@Serializable
data class ExecuteResponse(
    val ok: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

private fun ExecuteRequest.toSandboxRequest(mountSourcePolicy: MountSourcePolicy): BwrapSandboxRequest =
    BwrapSandboxRequest(
        command = command,
        workdir = workdir,
        timeoutSeconds = timeoutSeconds,
        networkEnabled = networkEnabled,
        mounts = mounts.map { it.toBwrapMount(mountSourcePolicy) },
    )

private fun MountRequest.toBwrapMount(mountSourcePolicy: MountSourcePolicy): BwrapMount {
    val targetPath = target.trim()
    require(targetPath.startsWith("/")) { "Mount target must be an absolute sandbox path: $target" }

    return BwrapMount(
        source = mountSourcePolicy.validate(Path.of(source)),
        target = targetPath,
        mode = parseMode(mode),
    )
}

private fun parseMode(mode: String): BwrapMountMode =
    when (mode.trim().lowercase()) {
        "ro" -> BwrapMountMode.RO
        "rw" -> BwrapMountMode.RW
        else -> throw IllegalArgumentException("Mount mode must be ro or rw: $mode")
    }

private fun BwrapSandboxResult.toResponse(): ExecuteResponse =
    ExecuteResponse(
        ok = ok,
        exitCode = exitCode,
        timedOut = timedOut,
        outputTruncated = outputTruncated,
        output = output,
        workdir = workdir,
    )
