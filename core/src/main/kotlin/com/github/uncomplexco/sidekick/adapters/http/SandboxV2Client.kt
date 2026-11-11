package com.github.uncomplexco.sidekick.adapters.http

import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPage
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPageOptions
import com.github.uncomplexco.sidekick.ports.filesystem.FileMetadata
import com.github.uncomplexco.sidekick.ports.filesystem.GlobResult
import com.github.uncomplexco.sidekick.ports.filesystem.GrepResult
import com.github.uncomplexco.sidekick.ports.filesystem.PatchResult
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadResult
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.ExecutionResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class SandboxV2Client(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    private val logger = LoggerFactory.getLogger(SandboxV2Client::class.java)

    fun execute(projectId: String, command: Command): ExecutionResult {
        val body =
            json.encodeToString(
                ProjectExecuteRequest(
                    projectId,
                    command.command,
                    command.timeoutSeconds,
                    command.workdir,
                )
            )
        val request =
            HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/v2/execute"))
                .timeout(Duration.ofSeconds(command.timeoutSeconds + 5))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        logger.info(
            "Sending project sandbox HTTP command: projectId={} workdir={} timeoutSeconds={}",
            projectId,
            command.workdir,
            command.timeoutSeconds,
        )
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in HTTP_SUCCESS_CODES) {
            "Bash sandbox service returned HTTP ${response.statusCode()}: ${response.body()}"
        }

        val result = json.decodeFromString<ProjectExecuteResponse>(response.body())

        return ExecutionResult(
            result.ok,
            result.exitCode,
            result.timedOut,
            result.outputTruncated,
            result.output,
            result.workdir,
        )
    }

    suspend fun stat(projectId: String, path: String): FileMetadata? =
        TODO("Sandbox v2 filesystem stat is not implemented")

    suspend fun readText(
        projectId: String,
        path: String,
        options: TextReadOptions,
    ): TextReadResult = TODO("Sandbox v2 filesystem readText is not implemented")

    suspend fun writeText(projectId: String, path: String, content: String): Unit =
        TODO("Sandbox v2 filesystem writeText is not implemented")

    suspend fun editText(
        projectId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): Unit = TODO("Sandbox v2 filesystem editText is not implemented")

    suspend fun applyPatch(projectId: String, path: String, patchText: String): PatchResult =
        TODO("Sandbox v2 filesystem applyPatch is not implemented")

    suspend fun listDirectory(
        projectId: String,
        path: String,
        options: DirectoryPageOptions,
    ): DirectoryPage = TODO("Sandbox v2 filesystem listDirectory is not implemented")

    suspend fun glob(projectId: String, path: String, pattern: String): GlobResult =
        TODO("Sandbox v2 filesystem glob is not implemented")

    suspend fun grep(
        projectId: String,
        path: String,
        pattern: String,
        include: String?,
    ): GrepResult = TODO("Sandbox v2 filesystem grep is not implemented")

    private companion object {
        val HTTP_SUCCESS_CODES = 200..299
    }
}

@Serializable
private data class ProjectExecuteRequest(
    val projectId: String,
    val command: String,
    val timeoutSeconds: Long,
    val workdir: String? = null,
)

@Serializable
private data class ProjectExecuteResponse(
    val ok: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)
