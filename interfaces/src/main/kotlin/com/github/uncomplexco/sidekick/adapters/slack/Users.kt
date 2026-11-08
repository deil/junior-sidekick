package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatUserDetails
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val usernamesCache = ConcurrentHashMap<String, MessageAuthor>()

internal suspend fun loadSlackUserDetails(client: MethodsClient, chatId: String): ChatUserDetails? {
    Loggers.SLACK.info("Loading Slack user details userId={}", chatId)
    val response =
        try {
            withContext(Dispatchers.IO) { client.usersInfo { it.user(chatId) } }
        } catch (error: IOException) {
            Loggers.SLACK.warn("Could not load Slack user details userId={}", chatId, error)
            return null
        } catch (error: SlackApiException) {
            Loggers.SLACK.warn("Could not load Slack user details userId={}", chatId, error)
            return null
        }

    if (!response.isOk) {
        Loggers.SLACK.warn(
            "Could not load Slack user details userId={}: {}",
            chatId,
            response.error,
        )
        return null
    }

    val user = response.user
    val profile = user.profile
    val email = profile?.email?.takeIf { it.isNotBlank() }
    if (email == null) {
        Loggers.SLACK.warn(
            "Cannot provision Slack user {} without email; users:read.email scope is required",
            chatId,
        )
        return null
    }

    val name =
        user.realName?.takeIf { it.isNotBlank() }
            ?: profile.displayName?.takeIf { it.isNotBlank() }
            ?: user.name?.takeIf { it.isNotBlank() }
            ?: chatId

    return ChatUserDetails(name, email)
}

internal fun toMessageAuthor(
    userId: String,
    ctx: EventContext,
): MessageAuthor =
    usernamesCache.computeIfAbsent(userId) {
        val userinfo = ctx.client().usersInfo { req -> req.user(userId) }

        if (!userinfo.isOk) {
            return@computeIfAbsent MessageAuthor(
                username = userId,
                fullName = null,
            )
        }

        return@computeIfAbsent MessageAuthor(
            username = userId,
            fullName =
                if (userinfo.user.profile.displayNameNormalized.isNullOrBlank()) {
                    userinfo.user.realName
                } else {
                    userinfo.user.profile.displayNameNormalized
                },
        )
    }
