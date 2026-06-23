package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.sandbox.SandboxCommand
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.pathString

class HttpSandboxExecutor(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = false },
) : SandboxExecutor {
    override fun execute(command: SandboxCommand): SandboxExecutionResult {
        val response = httpClient.send(command.toHttpRequest(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Bash sandbox service returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        val result = json.decodeFromString<ExecuteResponse>(response.body())
        return SandboxExecutionResult(
            ok = result.ok,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated,
            output = result.output,
            workdir = result.workdir,
        )
    }

    private fun SandboxCommand.toHttpRequest(): HttpRequest {
        val body =
            ExecuteRequest(
                command = command,
                workdir = workdir,
                timeoutSeconds = timeoutSeconds,
                networkEnabled = networkEnabled,
                mounts = mounts.map { it.toRequest() },
            )
        return HttpRequest
            .newBuilder(executeUri())
            .timeout(Duration.ofSeconds(timeoutSeconds + 5))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
            .build()
    }

    private fun SandboxMount.toRequest(): MountRequest =
        MountRequest(
            source = source.toAbsolutePath().normalize().pathString,
            target = target,
            mode = mode.toRequestMode(),
        )

    private fun SandboxMountMode.toRequestMode(): String =
        when (this) {
            SandboxMountMode.RO -> "ro"
            SandboxMountMode.RW -> "rw"
        }

    private fun executeUri(): URI = URI.create("${baseUrl.trimEnd('/')}/api/execute")
}

@Serializable
private data class ExecuteRequest(
    val command: String,
    val workdir: String,
    val timeoutSeconds: Long,
    val networkEnabled: Boolean,
    val mounts: List<MountRequest>,
)

@Serializable
private data class MountRequest(
    val source: String,
    val target: String,
    val mode: String,
)

@Serializable
private data class ExecuteResponse(
    val ok: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)
