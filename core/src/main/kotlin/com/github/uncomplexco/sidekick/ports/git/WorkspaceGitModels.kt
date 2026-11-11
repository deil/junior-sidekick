package com.github.uncomplexco.sidekick.ports.git

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitCloneResult(
    val path: String,
    val branch: String,
    @SerialName("commit_hash") val commitHash: String,
    val status: String,
)

@Serializable
data class GitPushResult(
    val path: String,
    val branch: String,
    @SerialName("commit_hash") val commitHash: String,
    val remote: String?,
    val upstream: String?,
    val dirty: Boolean,
    val status: String,
    val message: String,
)

@Serializable
data class GitPullResult(
    val path: String,
    val branch: String,
    @SerialName("commit_hash") val commitHash: String,
    val remote: String,
    val upstream: String,
    val status: String,
    val message: String,
)
