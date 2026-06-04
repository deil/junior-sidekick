package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFiles
import kotlinx.serialization.Serializable

class ReadFileTool(
    private val files: WorkspaceFiles,
) : Tool<ReadFileTool.Args, String>(
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = "__read_file",
        description =
            """
            Read a file or directory from the local filesystem. If the path does not exist, an error is returned.
            """.trimIndent(),
    ) {
    @Serializable
    data class Args(
        @property:LLMDescription("The absolute path to the file or directory to read")
        val filePath: String,
        @property:LLMDescription("The line number to start reading from (1-indexed)")
        val offset: Int? = null,
        @property:LLMDescription("The maximum number of lines to read (defaults to 2000)")
        val limit: Int? = null,
    )

    override suspend fun execute(args: Args): String = files.read(args.filePath, args.offset, args.limit)
}
