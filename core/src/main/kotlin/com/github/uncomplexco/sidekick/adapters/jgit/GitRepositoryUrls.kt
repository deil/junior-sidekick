package com.github.uncomplexco.sidekick.adapters.jgit

import java.net.URI

internal class GitRepositoryUrls {
    fun parse(url: String): GitRepositoryUrl {
        SCP_LIKE_URL_RE.matchEntire(url)?.let { match ->
            return repositoryUrl(match.groupValues[1], match.groupValues[2])
        }

        val uri =
            try {
                URI(url)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Unsupported Git repository URL", error)
            }
        val host =
            uri.host?.lowercase()
                ?: throw IllegalArgumentException("Unsupported Git repository URL")
        require(uri.scheme in setOf("https", "ssh")) { "Git repository URL must use SSH or HTTPS" }

        return repositoryUrl(host, uri.path)
    }

    private fun repositoryUrl(host: String, path: String): GitRepositoryUrl {
        val normalizedHost = host.lowercase()
        if (normalizedHost == "dev.azure.com" || normalizedHost == "ssh.dev.azure.com") {
            return azureDevOpsRepositoryUrl(normalizedHost, path)
        }

        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "Git repository URL must identify owner and repository" }
        val repository = parts.last().removeSuffix(".git")
        require(repository.isNotBlank()) { "Git repository URL must identify owner and repository" }
        val repositoryPath = (parts.dropLast(1) + repository).joinToString("/")
        val provider =
            when (normalizedHost) {
                "github.com" -> GitProvider.GITHUB
                "bitbucket.org" -> GitProvider.BITBUCKET
                else -> GitProvider.OTHER
            }

        return GitRepositoryUrl(
            provider,
            normalizedHost,
            repositoryPath,
            "git@$normalizedHost:$repositoryPath.git",
        )
    }

    private fun azureDevOpsRepositoryUrl(host: String, path: String): GitRepositoryUrl {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val coordinates =
            when {
                host == "dev.azure.com" &&
                    parts.size == AZURE_PATH_SEGMENTS &&
                    parts[2].equals("_git", true) ->
                    listOf(parts[0], parts[1], parts[AZURE_REPOSITORY_INDEX].removeSuffix(".git"))
                host == "ssh.dev.azure.com" &&
                    parts.size == AZURE_PATH_SEGMENTS &&
                    parts[0].equals("v3", true) ->
                    listOf(parts[1], parts[2], parts[AZURE_REPOSITORY_INDEX].removeSuffix(".git"))
                else -> throw IllegalArgumentException("Unsupported Azure DevOps repository URL")
            }
        require(coordinates.none { it.isBlank() }) {
            "Azure DevOps URL must identify organization, project, and repository"
        }
        val repositoryPath = coordinates.joinToString("/")

        return GitRepositoryUrl(
            GitProvider.AZURE_DEVOPS,
            "dev.azure.com",
            repositoryPath,
            "git@ssh.dev.azure.com:v3/$repositoryPath",
        )
    }

    private companion object {
        val SCP_LIKE_URL_RE = Regex("^git@([^/:\\s]+):(.+)$")
        const val AZURE_PATH_SEGMENTS = 4
        const val AZURE_REPOSITORY_INDEX = 3
    }
}

internal data class GitRepositoryUrl(
    val provider: GitProvider,
    val host: String,
    val repositoryPath: String,
    val sshUrl: String,
) {
    val canonical = "$host/${repositoryPath.lowercase()}"
}

internal enum class GitProvider(val displayName: String) {
    GITHUB("GitHub"),
    BITBUCKET("Bitbucket"),
    AZURE_DEVOPS("Azure DevOps"),
    OTHER("Git provider"),
}
