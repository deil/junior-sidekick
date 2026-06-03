package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.slack.api.bolt.context.builtin.EventContext
import java.util.concurrent.ConcurrentHashMap

internal val usernamesCache = ConcurrentHashMap<String, MessageAuthor>()

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
                if (userinfo.user.profile.displayNameNormalized
                        .isNullOrBlank()
                ) {
                    userinfo.user.realName
                } else {
                    userinfo.user.profile.displayNameNormalized
                },
        )
    }
