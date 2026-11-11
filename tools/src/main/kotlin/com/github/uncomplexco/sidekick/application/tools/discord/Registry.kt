package com.github.uncomplexco.sidekick.application.tools.discord

import ai.koog.agents.core.tools.ToolBase
import com.github.uncomplexco.sidekick.application.chat.DiscordBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.tools.asLlmTools

fun discordTools(chat: DiscordBackedChatPlatformAdapter): List<ToolBase<*, *>> =
    DiscordHistoryTools(chat::loadChannelHistory, chat::loadThreadHistory).asLlmTools()
