package com.github.uncomplexco.sidekick.sandbox.service

import java.io.IOException
import java.util.concurrent.FutureTask
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun interface ProjectCommandExecutor {
    fun execute(request: ProjectExecuteRequest): ExecuteResponse
}

private class SandboxHelperException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class HelperProjectCommandExecutor(
    private val helperPath: String,
    private val sudoPath: String,
    private val maxOutputBytes: Int,
) : ProjectCommandExecutor {
    private val logger = LoggerFactory.getLogger(HelperProjectCommandExecutor::class.java)

    override fun execute(request: ProjectExecuteRequest): ExecuteResponse {
        return try {
            executeCommand(request)
        } catch (error: SandboxHelperException) {
            logger.error("Project execution failed: projectId={}", request.projectId, error)
            val output = (error.message ?: "Sandbox helper failed").toByteArray(Charsets.UTF_8)
            ExecuteResponse(
                ok = false,
                exitCode = null,
                timedOut = false,
                outputTruncated = output.size > maxOutputBytes,
                output =
                    String(output, 0, output.size.coerceAtMost(maxOutputBytes), Charsets.UTF_8),
                workdir = request.workdir.orEmpty(),
            )
        }
    }

    private fun executeCommand(request: ProjectExecuteRequest): ExecuteResponse {
        val uid = invoke(listOf("user:ensure", "--project", request.projectId)).trim().toLong()
        logger.info("Ensured sandbox project user: projectId={} uid={}", request.projectId, uid)

        val arguments = buildList {
            addAll(listOf("bash", "--project", request.projectId))
            request.workdir?.let { addAll(listOf("--workdir", it)) }
            addAll(listOf("--timeout-seconds", request.timeoutSeconds.toString()))
            addAll(listOf("--max-output-bytes", maxOutputBytes.toString(), "--json"))
            addAll(listOf("--", request.command))
        }
        val result = Json.decodeFromString<ExecuteResponse>(invoke(arguments))
        logger.info(
            "Executed sandbox project command: projectId={} exitCode={} timedOut={}",
            request.projectId,
            result.exitCode,
            result.timedOut,
        )
        return result
    }

    private fun invoke(arguments: List<String>): String {
        val process =
            try {
                ProcessBuilder(listOf(sudoPath, "-n", "--", helperPath) + arguments).start()
            } catch (error: IOException) {
                throw SandboxHelperException(
                    "Cannot start sandbox helper during ${arguments.first()}: ${error.message}",
                    error,
                )
            }
        process.outputStream.close()
        val stderr = FutureTask { process.errorStream.bufferedReader().use { it.readText() } }
        Thread.ofVirtual().start(stderr)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val diagnostic = stderr.get().trim()
        if (exitCode != 0) {
            val detail = diagnostic.ifBlank { "No diagnostic output" }
            throw SandboxHelperException(
                "Sandbox helper failed during ${arguments.first()} (exit $exitCode): $detail"
            )
        }
        if (diagnostic.isNotBlank()) {
            logger.debug("Sandbox helper {}: {}", arguments.first(), diagnostic)
        }
        return output
    }
}
