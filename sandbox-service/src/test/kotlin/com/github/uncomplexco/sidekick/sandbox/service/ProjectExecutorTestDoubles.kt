package com.github.uncomplexco.sidekick.sandbox.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString

class RecordingProjectCommandExecutor : ProjectCommandExecutor {
    val requests = mutableListOf<ProjectExecuteRequest>()
    val response = ExecuteResponse(true, 0, false, false, "done\n", "/home/project")

    override fun execute(request: ProjectExecuteRequest): ExecuteResponse {
        requests.add(request)
        return response
    }
}

class StubProjectHelper(private val directory: Path) {
    val helper = install("helper-stub.cjs")
    val sudo = install("sudo-stub.cjs")

    fun executor() = HelperProjectCommandExecutor(helper.pathString, sudo.pathString, 1234)

    fun failEnsure() {
        Files.createFile(directory.resolve("fail-ensure"))
    }

    fun failSudo() {
        Files.createFile(directory.resolve("fail-sudo"))
    }

    fun invocations(): List<String> = Files.readAllLines(directory.resolve("invocations.jsonl"))

    private fun install(name: String): Path {
        val target = directory.resolve(name)
        javaClass.getResourceAsStream("/$name")!!.use { Files.copy(it, target) }
        check(target.toFile().setExecutable(true))
        return target
    }
}
