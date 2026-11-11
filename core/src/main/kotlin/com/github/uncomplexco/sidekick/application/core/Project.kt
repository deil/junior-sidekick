package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.chat.ChannelKind
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import java.time.Instant

data class Project(
    val uid: String,
    val adapter: ChatPlatform,
    val name: String,
    val externalRefId: String,
    val createdAt: Instant,
    val type: ProjectType = ProjectType.PRIVATE,
    val id: Long? = null,
    val channelKind: ChannelKind? = null,
    val isShared: Boolean? = null,
    val channelDetailsUpdatedAt: Instant? = null,
    val chatWorkspaceId: String? = null,
) {
    val isMissingChatMetadata: Boolean
        get() = chatWorkspaceId == null || channelKind == null || isShared == null
}

enum class ProjectType {
    PUBLIC,
    PRIVATE,
    DM,
}
