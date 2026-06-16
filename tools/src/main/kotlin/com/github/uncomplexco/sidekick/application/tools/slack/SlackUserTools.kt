package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.slack.api.methods.MethodsClient
import com.slack.api.model.User
import kotlinx.serialization.Serializable

private const val DEFAULT_USER_LIMIT = 5
private const val MAX_USER_LIMIT = 25
private const val DEFAULT_SCAN_DEPTH = 2
private const val MAX_SCAN_DEPTH = 5
private const val PAGE_LIMIT = 200

@LLMDescription("Slack tools for finding users by id, email, username, display name, or real name")
class SlackUserTools(
    private val slackClient: MethodsClient,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Search Slack users. Supply exactly one of userId, email, or name. userId and email are direct lookups. name searches username, display name, and real name by scanning Slack user-list pages.",
    )
    fun slackUserSearch(
        @LLMDescription("Slack user id for direct lookup, for example U123.")
        userId: String? = null,
        @LLMDescription("Email address for direct lookup.")
        email: String? = null,
        @LLMDescription("Name text to search for. Matches username, display name, real name. Requires at least 2 characters.")
        name: String? = null,
        @LLMDescription("Maximum users to return when searching by name. Defaults to 5 and is capped at 25.")
        limit: Int? = null,
        @LLMDescription("Maximum Slack API pages to scan when searching by name. Defaults to 2 and is capped at 5.")
        scan_depth: Int? = null,
    ): SlackUserSearchResult {
        val (normalizedUserId, normalizedEmail, normalizedName) = normalizeSlackUserSearchKeys(userId, email, name)

        val users =
            when {
                normalizedUserId != null -> listOfNotNull(lookupUser(normalizedUserId))
                normalizedEmail != null -> listOfNotNull(lookupEmail(normalizedEmail))
                else -> searchName(requireNotNull(normalizedName), limit.normalizedLimit(), scan_depth.normalizedScanDepth())
            }

        return SlackUserSearchResult(ok = true, users = users.map { it.toMatch() })
    }

    private fun lookupUser(userId: String): User? {
        val response = slackClient.usersInfo { it.user(userId) }
        if (response.isOk) {
            return response.user
        }
        if (response.error == "user_not_found") {
            return null
        }
        throw ToolException.ValidationFailure(response.error ?: "Failed to look up Slack user.")
    }

    private fun lookupEmail(email: String): User? {
        val response = slackClient.usersLookupByEmail { it.email(email) }
        if (response.isOk) {
            return response.user
        }
        if (response.error == "users_not_found") {
            return null
        }
        throw ToolException.ValidationFailure(response.error ?: "Failed to look up Slack user by email.")
    }

    private fun searchName(
        name: String,
        limit: Int,
        scanDepth: Int,
    ): List<User> {
        if (name.length < 2) {
            throw ToolException.ValidationFailure("Slack user name search requires at least 2 characters.")
        }

        val query = name.lowercase()
        val users = mutableListOf<User>()
        var cursor: String? = null
        repeat(scanDepth) {
            val response =
                slackClient.usersList { req ->
                    req.limit(PAGE_LIMIT)
                    cursor?.let(req::cursor)
                    req
                }
            if (!response.isOk) {
                throw ToolException.ValidationFailure(response.error ?: "Failed to list Slack users.")
            }

            users +=
                response.members
                    .orEmpty()
                    .filter { it.matches(query) }
                    .take(limit - users.size)
            cursor = response.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
            if (users.size >= limit || cursor == null) {
                return users
            }
        }
        return users
    }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotBlank() }

fun Int?.normalizedSlackUserLimit(): Int {
    val value = this ?: DEFAULT_USER_LIMIT
    if (value < 1) {
        throw ToolException.ValidationFailure("Slack user search limit must be greater than or equal to 1.")
    }
    return minOf(value, MAX_USER_LIMIT)
}

fun Int?.normalizedSlackUserScanDepth(): Int {
    val value = this ?: DEFAULT_SCAN_DEPTH
    if (value < 1) {
        throw ToolException.ValidationFailure("Slack user search scan_depth must be greater than or equal to 1.")
    }
    return minOf(value, MAX_SCAN_DEPTH)
}

fun normalizeSlackUserSearchKeys(
    userId: String?,
    email: String?,
    name: String?,
): Triple<String?, String?, String?> {
    val normalizedUserId = userId.clean()
    val normalizedEmail = email.clean()
    val normalizedName = name.clean()
    if (listOfNotNull(normalizedUserId, normalizedEmail, normalizedName).size != 1) {
        throw ToolException.ValidationFailure("Supply exactly one of userId, email, or name.")
    }
    return Triple(normalizedUserId, normalizedEmail, normalizedName)
}

private fun Int?.normalizedLimit(): Int = this.normalizedSlackUserLimit()

private fun Int?.normalizedScanDepth(): Int = this.normalizedSlackUserScanDepth()

private fun User.matches(query: String): Boolean =
    listOfNotNull(name, profile?.displayName, profile?.displayNameNormalized, realName, profile?.realName, profile?.realNameNormalized)
        .any { it.lowercase().contains(query) }

private fun User.toMatch(): SlackUserSearchMatch =
    SlackUserSearchMatch(
        id = id,
        username = name,
        displayName = profile?.displayNameNormalized ?: profile?.displayName,
        realName = profile?.realNameNormalized ?: profile?.realName ?: realName,
        email = profile?.email,
        isBot = isBot,
    )

@Serializable
data class SlackUserSearchResult(
    val ok: Boolean,
    val users: List<SlackUserSearchMatch>,
)

@Serializable
data class SlackUserSearchMatch(
    val id: String?,
    val username: String?,
    val displayName: String?,
    val realName: String?,
    val email: String?,
    val isBot: Boolean,
)
