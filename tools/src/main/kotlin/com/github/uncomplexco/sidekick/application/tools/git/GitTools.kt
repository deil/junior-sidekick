package com.github.uncomplexco.sidekick.application.tools.git

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.git.GitCloneResult
import com.github.uncomplexco.sidekick.ports.git.GitPullResult
import com.github.uncomplexco.sidekick.ports.git.GitPushResult
import com.github.uncomplexco.sidekick.ports.git.WorkspaceGit
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class GitTools(private val projectId: String, private val git: WorkspaceGit) : ToolSet {
    private val logger = LoggerFactory.getLogger(Loggers.TOOLS.name + ".git")

    @Tool("git__clone")
    @LLMDescription(
        "Clone or fetch and fast-forward a private Git repository into the project workspace"
    )
    fun clone(
        @LLMDescription("Git repository URL. Both SSH and HTTPS clone URLs are supported")
        url: String,
        @LLMDescription("Destination folder path in the project workspace or session scratch space")
        path: String,
    ): GitCloneResult {
        validate(url.isNotBlank()) { "url is required" }
        validate(path.isNotBlank()) { "path is required" }

        return try {
            runBlocking { git.clone(projectId, url, path) }
        } catch (error: Exception) {
            logger.error("Git clone failed path={}", path, error)
            throw ToolException.ValidationFailure(error.message ?: "Git clone failed")
        }
    }

    @Tool("git__push")
    @LLMDescription(
        "Update remote refs along with associated objects. " +
            "Use instead of direct 'git push' invocation for private repositories"
    )
    fun push(
        @LLMDescription("Repository folder path") path: String,
        @LLMDescription(
            "What destination ref to update with what source object. " +
                "Equivalent to 'git push origin <branch>'. Defaults to the current branch upstream"
        )
        refspec: String? = null,
        @LLMDescription(
            "Push all branches (i.e. refs under refs/heads/); cannot be used with other <refspec>. " +
                "Equivalent to 'git push --all'"
        )
        all: Boolean = false,
        @LLMDescription(
            "All refs under refs/tags are pushed, in addition to refspecs explicitly listed in <refspec>. " +
                "Equivalent to 'git push --tags'"
        )
        tags: Boolean = false,
    ): GitPushResult {
        validate(path.isNotBlank()) { "'path' is required" }
        validate(refspec == null || refspec.isNotBlank()) { "'branch' must not be blank" }
        validate(!all || refspec == null) { "'all' cannot be used with 'refspec'" }

        return invoke(path) { git.push(projectId, path, refspec, all, tags) }
    }

    @Tool("git__pull")
    @LLMDescription(
        "Fetch from and integrate with another repository or a local branch. " +
            "Use instead of direct 'git pull' invocation for private repositories"
    )
    fun pull(
        @LLMDescription("Repository folder path") path: String,
        @LLMDescription("Name of a remote that is the source of a fetch or pull operation")
        remote: String,
        @LLMDescription("Specifies which refs to fetch and which local refs to update")
        refspec: String,
    ): GitPullResult {
        validate(path.isNotBlank()) { "'path' is required" }
        validate(remote.isNotBlank()) { "'remote' is required" }
        validate(refspec.isNotBlank()) { "'refspec' is required" }

        return invoke(path) { git.pull(projectId, path, remote, refspec) }
    }

    private fun <T> invoke(path: String, operation: suspend () -> T): T =
        try {
            runBlocking { operation() }
        } catch (error: IllegalArgumentException) {
            logger.error("Git operation failed path={}", path, error)
            throw ToolException.ValidationFailure(error.message ?: "Invalid Git request")
        } catch (error: Exception) {
            logger.error("Git operation failed path={}", path, error)
            throw error
        }
}
