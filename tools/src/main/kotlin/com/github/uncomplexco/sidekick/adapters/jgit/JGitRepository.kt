package com.github.uncomplexco.sidekick.adapters.jgit

import com.github.uncomplexco.sidekick.application.tools.git.GitPushPlan
import com.github.uncomplexco.sidekick.application.tools.git.GitPushState
import com.github.uncomplexco.sidekick.application.tools.git.GitPushStatus
import com.github.uncomplexco.sidekick.application.tools.git.GitRepository
import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryState
import com.github.uncomplexco.sidekick.application.tools.git.GitRepositoryStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import java.nio.file.Path

class JGitRepository : GitRepository {
    override fun clone(
        url: String,
        sshKeyFile: String,
        checkout: Path,
        workingDirectory: Path,
        preferredBranches: List<String>,
    ): GitRepositoryState {
        Git
            .cloneRepository()
            .setURI(url)
            .setDirectory(checkout.toFile())
            .applySsh(sshKeyFile, workingDirectory)
            .call()
            .use { git -> checkoutPreferredBranch(git, preferredBranches) }
        return state(checkout, GitRepositoryStatus.CLONED)
    }

    override fun fetch(
        checkout: Path,
        sshKeyFile: String,
    ): GitRepositoryState {
        Git.open(checkout.toFile()).use { git ->
            git
                .fetch()
                .setRemote(REMOTE_ORIGIN)
                .setRemoveDeletedRefs(true)
                .applySsh(sshKeyFile, checkout)
                .call()
            return state(checkout, fastForwardCurrentBranch(git))
        }
    }

    override fun isGitRepository(checkout: Path): Boolean =
        runCatching {
            Git.open(checkout.toFile()).close()
            true
        }.getOrDefault(false)

    override fun originUrl(checkout: Path): String? =
        Git.open(checkout.toFile()).use { git ->
            git.repository.config.getString("remote", REMOTE_ORIGIN, "url")
        }

    override fun pushPlan(checkout: Path): GitPushPlan =
        Git.open(checkout.toFile()).use { git ->
            git.pushPlan(checkout)
        }

    override fun push(
        checkout: Path,
        sshKeyFile: String,
        tags: Boolean,
    ): GitPushState {
        Git.open(checkout.toFile()).use { git ->
            val plan = git.pushPlan(checkout)
            plan.status?.let { return plan.toState(it, plan.message) }

            return runCatching {
                val resultStatuses =
                    git
                        .push()
                        .setRemote(plan.remote!!)
                        .setRefSpecs(pushRefSpecs(plan, tags))
                        .applySsh(sshKeyFile, checkout)
                        .call()
                        .flatMap { result -> result.remoteUpdates }
                val status = resultStatuses.map { it.status.toPushStatus() }.worstOrNull() ?: GitPushStatus.UP_TO_DATE
                val message = resultStatuses.toPushMessage()
                plan.toState(status, message)
            }.getOrElse { error ->
                plan.toState(GitPushStatus.FAILED, error.message ?: "Git push failed")
            }
        }
    }

    private fun checkoutPreferredBranch(
        git: Git,
        preferredBranches: List<String>,
    ) {
        val selectedBranch =
            preferredBranches.firstOrNull { branch -> git.repository.findRef("refs/remotes/$REMOTE_ORIGIN/$branch") != null }
                ?: return

        if (git.repository.branch == selectedBranch) {
            return
        }

        git
            .checkout()
            .setCreateBranch(true)
            .setName(selectedBranch)
            .setStartPoint("$REMOTE_ORIGIN/$selectedBranch")
            .call()
    }

    private fun fastForwardCurrentBranch(git: Git): GitRepositoryStatus {
        val branch = git.repository.branch ?: return GitRepositoryStatus.FETCHED_DETACHED_HEAD
        val head = git.repository.resolve(HEAD) ?: return GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED
        val remote =
            git.repository.findRef("refs/remotes/$REMOTE_ORIGIN/$branch")?.objectId ?: return GitRepositoryStatus.FETCHED_NO_REMOTE_BRANCH
        if (head == remote) {
            return GitRepositoryStatus.FETCHED_UP_TO_DATE
        }

        val canFastForward =
            RevWalk(git.repository).use { walk ->
                walk.isMergedInto(walk.parseCommit(head), walk.parseCommit(remote))
            }
        if (!canFastForward) {
            return GitRepositoryStatus.FETCHED_DIVERGED
        }

        return runCatching {
            val result =
                git
                    .merge()
                    .include(remote)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .call()
            when (result.mergeStatus) {
                MergeResult.MergeStatus.FAST_FORWARD -> GitRepositoryStatus.FETCHED_FAST_FORWARDED
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> GitRepositoryStatus.FETCHED_UP_TO_DATE
                else -> GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED
            }
        }.getOrDefault(GitRepositoryStatus.FETCHED_FAST_FORWARD_FAILED)
    }

    private fun state(
        checkout: Path,
        status: GitRepositoryStatus,
    ): GitRepositoryState =
        Git.open(checkout.toFile()).use { git ->
            GitRepositoryState(
                path = checkout,
                branch = git.repository.currentBranch(),
                commitHash = git.repository.resolve(HEAD).name,
                status = status,
            )
        }

    private fun pushRefSpecs(
        plan: GitPushPlan,
        tags: Boolean,
    ): List<RefSpec> =
        buildList {
            add(RefSpec("refs/heads/${plan.branch}:${plan.upstream!!}"))
            if (tags) {
                add(RefSpec("refs/tags/*:refs/tags/*"))
            }
        }

    companion object {
        const val REMOTE_ORIGIN = "origin"
        const val HEAD = "HEAD"
    }
}
