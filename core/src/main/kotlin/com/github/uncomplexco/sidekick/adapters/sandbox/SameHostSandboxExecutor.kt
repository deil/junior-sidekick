package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.ExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.pathString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class SameHostSandboxExecutor(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = false },
) : SandboxExecutor {
    private val logger = LoggerFactory.getLogger(SameHostSandboxExecutor::class.java)

    override fun execute(projectId: String, command: Command): ExecutionResult {
        logger.info(
            "Sending sandbox HTTP command: workdir={} timeoutSeconds={} networkEnabled={} mounts={}",
            command.workdir,
            command.timeoutSeconds,
            command.networkEnabled,
            command.mounts.map {
                "${it.mode.name.lowercase()}:${it.source.toAbsolutePath().normalize()}->${it.target} " +
                    fileAttributes(it.source)
            },
        )
        val response =
            httpClient.send(command.toHttpRequest(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in HTTP_SUCCESS_CODES) {
            throw IllegalStateException(
                "Bash sandbox service returned HTTP ${response.statusCode()}: ${response.body()}"
            )
        }

        val result = json.decodeFromString<ExecuteResponse>(response.body())

        return ExecutionResult(
            ok = result.ok,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated,
            output = result.output,
            workdir = result.workdir,
        )
    }

    private fun Command.toHttpRequest(): HttpRequest {
        val body =
            json.encodeToString(
                ExecuteRequest(
                    command = command,
                    workdir = workdir,
                    timeoutSeconds = timeoutSeconds,
                    networkEnabled = networkEnabled,
                    mounts = mounts.map { it.toRequest() },
                )
            )

        return HttpRequest.newBuilder(executeUri())
            .timeout(Duration.ofSeconds(timeoutSeconds + HTTP_TIMEOUT_GRACE_SECONDS))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
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

    private fun executeUri(): URI {
        return URI.create("${baseUrl.trimEnd('/')}/api/execute")
    }

    private fun fileAttributes(path: Path): String =
        try {
            val normalized = path.toAbsolutePath().normalize()
            val uid = Files.getAttribute(normalized, "unix:uid")
            val gid = Files.getAttribute(normalized, "unix:gid")
            val mode = (Files.getAttribute(normalized, "unix:mode") as Int) and UNIX_MODE_MASK

            "uid=$uid gid=$gid mode=${mode.toString(OCTAL_RADIX)}"
        } catch (error: UnsupportedOperationException) {
            logger.warn("Unix mount attributes unavailable path={}", path, error)

            "unix-attributes-unavailable"
        }

    private companion object {
        val HTTP_SUCCESS_CODES = 200..299
        const val HTTP_TIMEOUT_GRACE_SECONDS = 5L
        const val UNIX_MODE_MASK = 0b111111111111
        const val OCTAL_RADIX = 8
    }
}

@Serializable
private data class ExecuteRequest(
    val command: String,
    val workdir: String? = null,
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
