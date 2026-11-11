package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.jgit.GitPullState
import com.github.uncomplexco.sidekick.adapters.jgit.GitPullStatus
import com.github.uncomplexco.sidekick.adapters.jgit.GitPushPlan
import com.github.uncomplexco.sidekick.adapters.jgit.GitPushState
import com.github.uncomplexco.sidekick.adapters.jgit.GitPushStatus
import com.github.uncomplexco.sidekick.adapters.jgit.GitRepository
import com.github.uncomplexco.sidekick.adapters.jgit.GitRepositoryState
import com.github.uncomplexco.sidekick.adapters.jgit.GitRepositoryStatus
import java.nio.file.Path

class FakeGitRepository(
    private val gitRepositories: Set<Path> = emptySet(),
    private val origins: Map<Path, String> = emptyMap(),
    private val pushPlan: ((Path, String?) -> GitPushPlan)? = null,
) : GitRepository {
    var clonedUrl: String? = null
    var clonedSshKeyFile: String? = null
    var clonedCheckout: Path? = null
    var clonedPreferredBranches: List<String>? = null
    var fetchedCheckout: Path? = null
    var pushedCheckout: Path? = null
    var pushedSshKeyFile: String? = null
    var pushedBranch: String? = null
    var pushedAll: Boolean? = null
    var pushedTags: Boolean? = null
    var pulledCheckout: Path? = null
    var pulledSshKeyFile: String? = null
    var pulledRemote: String? = null
    var pulledRefspec: String? = null
    var updatedRemote: Triple<Path, String, String>? = null

    override fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState {
        clonedUrl = url
        clonedSshKeyFile = sshKeyFile
        clonedCheckout = checkout
        clonedPreferredBranches = preferredBranches

        return GitRepositoryState(checkout, "develop", "abc123", GitRepositoryStatus.CLONED)
    }

    override fun fetch(checkout: Path, sshKeyFile: String): GitRepositoryState {
        fetchedCheckout = checkout

        return GitRepositoryState(
            checkout,
            "main",
            "def456",
            GitRepositoryStatus.FETCHED_FAST_FORWARDED,
        )
    }

    override fun isGitRepository(checkout: Path): Boolean = checkout in gitRepositories

    override fun originUrl(checkout: Path): String? = origins[checkout]

    override fun remoteUrl(checkout: Path, remote: String): String? = origins[checkout]

    override fun setRemoteUrl(checkout: Path, remote: String, url: String) {
        updatedRemote = Triple(checkout, remote, url)
    }

    override fun pushPlan(checkout: Path, branch: String?): GitPushPlan {
        val selectedBranch = branch ?: "main"

        return pushPlan?.invoke(checkout, branch)
            ?: GitPushPlan(
                path = checkout,
                branch = selectedBranch,
                commitHash = "ghi789",
                dirty = true,
                remote = "origin",
                upstream = "refs/heads/$selectedBranch",
                remoteUrl = "git@github.com:acme/repo.git",
                status = null,
                message = "Ready",
            )
    }

    override fun push(
        checkout: Path,
        sshKeyFile: String,
        branch: String?,
        all: Boolean,
        tags: Boolean,
    ): GitPushState {
        val selectedBranch = branch ?: "main"

        pushedCheckout = checkout
        pushedSshKeyFile = sshKeyFile
        pushedBranch = branch
        pushedAll = all
        pushedTags = tags

        return GitPushState(
            path = checkout,
            branch = selectedBranch,
            commitHash = "ghi789",
            dirty = true,
            remote = "origin",
            upstream = "refs/heads/$selectedBranch",
            status = GitPushStatus.PUSHED,
            message = "refs/heads/$selectedBranch: ok",
        )
    }

    override fun pull(
        checkout: Path,
        sshKeyFile: String,
        remote: String,
        refspec: String,
    ): GitPullState {
        pulledCheckout = checkout
        pulledSshKeyFile = sshKeyFile
        pulledRemote = remote
        pulledRefspec = refspec

        return GitPullState(
            path = checkout,
            branch = "main",
            commitHash = "jkl012",
            remote = remote,
            upstream = refspec,
            status = GitPullStatus.FAST_FORWARDED,
            message = "FAST_FORWARD",
        )
    }
}
