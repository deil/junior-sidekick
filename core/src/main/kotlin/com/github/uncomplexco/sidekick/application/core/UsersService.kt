package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.ports.UsersRepository
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class UsersService(
    private val users: UsersRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun record(messages: List<IncomingMessage>, chat: ChatPlatformAdapter) {
        for (message in messages) {
            val chatId = message.sender.username
            val now = clock.instant()

            val existing = users.findByChatId(chatId)
            if (existing != null) {
                val details =
                    if (existing.isMissingChatMetadata) chat.loadUserDetails(chatId) else null
                if (details != null) {
                    users.updateChatDetails(
                        chatId,
                        existing.copy(
                            lastSeenAt = now,
                            chatWorkspaceId = details.workspaceId ?: existing.chatWorkspaceId,
                            isBot = details.isBot ?: existing.isBot,
                            isGuest = details.isGuest ?: existing.isGuest,
                            chatDetailsUpdatedAt = clock.instant(),
                        ),
                    )

                    log.info(
                        "Updated chat metadata and recorded interaction for user chatId={}",
                        chatId,
                    )
                } else {
                    users.updateLastSeenByChatId(chatId, now)

                    log.info("Recorded chat interaction for user chatId={}", chatId)
                }
                continue
            }

            val details = chat.loadUserDetails(chatId) ?: continue
            val user =
                User(
                    name = details.name,
                    email = details.email,
                    idpId = null,
                    chatId = chatId,
                    createdAt = now,
                    lastSeenAt = now,
                    chatWorkspaceId = details.workspaceId,
                    isBot = details.isBot,
                    isGuest = details.isGuest,
                    chatDetailsUpdatedAt = now,
                )
            val email = details.email
            if (email != null && users.findByEmail(email) != null) {
                users.linkChatId(email, user)

                log.info("Linked chat identity and recorded interaction chatId={}", chatId)
                continue
            }

            users.add(user)

            log.info("Provisioned chat user chatId={}", chatId)
        }
    }
}
