package com.github.uncomplexco.sidekick.application.core

import java.time.Instant

data class User(
    val name: String,
    val email: String?,
    val idpId: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null,
    val chatId: String? = null,
    val id: Long? = null,
    val chatWorkspaceId: String? = null,
    val isBot: Boolean? = null,
    val isGuest: Boolean? = null,
    val chatDetailsUpdatedAt: Instant? = null,
) {
    val isMissingChatMetadata: Boolean
        get() = chatWorkspaceId == null || isBot == null || isGuest == null
}
