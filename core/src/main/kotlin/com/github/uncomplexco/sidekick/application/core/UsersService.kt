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

            if (users.findByChatId(chatId) != null) {
                users.updateLastSeenByChatId(chatId, now)

                log.info("Recorded chat interaction for user chatId={}", chatId)
                continue
            }

            val details = chat.loadUserDetails(chatId) ?: continue
            if (users.findByEmail(details.email) != null) {
                users.linkChatId(details.email, chatId, now)

                log.info("Linked chat identity and recorded interaction chatId={}", chatId)
                continue
            }

            users.add(
                User(
                    name = details.name,
                    email = details.email,
                    idpId = null,
                    chatId = chatId,
                    createdAt = now,
                    lastSeenAt = now,
                )
            )

            log.info("Provisioned chat user chatId={}", chatId)
        }
    }
}
