package com.github.uncomplexco.sidekick.sandbox.service

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun interface ProjectCommandExecutor {
    fun execute(request: ProjectExecuteRequest): ExecuteResponse
}

class HelperProjectCommandExecutor(
    private val helperPath: String,
    private val sudoPath: String,
    private val maxOutputBytes: Int,
) : ProjectCommandExecutor {
    private val logger = LoggerFactory.getLogger(HelperProjectCommandExecutor::class.java)

    override fun execute(request: ProjectExecuteRequest): ExecuteResponse {
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
            ProcessBuilder(listOf(sudoPath, "-n", "--", helperPath) + arguments)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Sandbox helper failed during ${arguments.first()}" }
        return output
    }
}
