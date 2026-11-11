package com.github.uncomplexco.sidekick.adapters.jgit

import java.nio.file.Path

interface GitRepository {
    fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState

    fun fetch(checkout: Path, sshKeyFile: String): GitRepositoryState

    fun isGitRepository(checkout: Path): Boolean

    fun originUrl(checkout: Path): String?

    fun remoteUrl(checkout: Path, remote: String): String?

    fun setRemoteUrl(checkout: Path, remote: String, url: String)

    fun pushPlan(checkout: Path, branch: String?): GitPushPlan

    fun push(
        checkout: Path,
        sshKeyFile: String,
        branch: String?,
        all: Boolean,
        tags: Boolean,
    ): GitPushState

    fun pull(checkout: Path, sshKeyFile: String, remote: String, refspec: String): GitPullState
}

data class GitRepositoryState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val status: GitRepositoryStatus,
)

data class GitPushPlan(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val dirty: Boolean,
    val remote: String?,
    val upstream: String?,
    val remoteUrl: String?,
    val status: GitPushStatus?,
    val message: String,
) {
    fun toState(status: GitPushStatus, message: String): GitPushState =
        GitPushState(path, branch, commitHash, dirty, remote, upstream, status, message)
}

data class GitPushState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val dirty: Boolean,
    val remote: String?,
    val upstream: String?,
    val status: GitPushStatus,
    val message: String,
)

data class GitPullState(
    val path: Path,
    val branch: String,
    val commitHash: String,
    val remote: String,
    val upstream: String,
    val status: GitPullStatus,
    val message: String,
)

enum class GitRepositoryStatus {
    CLONED,
    FETCHED_FAST_FORWARDED,
    FETCHED_UP_TO_DATE,
    FETCHED_DIVERGED,
    FETCHED_FAST_FORWARD_FAILED,
    FETCHED_DETACHED_HEAD,
    FETCHED_NO_REMOTE_BRANCH,
}

enum class GitPushStatus {
    PUSHED,
    UP_TO_DATE,
    REJECTED_NON_FAST_FORWARD,
    REJECTED_REMOTE_CHANGED,
    REJECTED_OTHER_REASON,
    NON_EXISTING_REMOTE_REF,
    AWAITING_REPORT,
    NOT_ATTEMPTED,
    FAILED,
    DETACHED_HEAD,
    NO_UPSTREAM,
    NO_REMOTE,
}

enum class GitPullStatus {
    FAST_FORWARDED,
    UP_TO_DATE,
    MERGED,
    CONFLICTING,
    CHECKOUT_CONFLICT,
    FAILED,
}
