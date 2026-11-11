package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.User
import java.time.Instant

interface UsersRepository {
    fun add(user: User)

    fun findAllByIdpId(idpId: String): List<User>

    fun findByChatId(chatId: String): User?

    fun findByEmail(email: String): User?

    fun updateLastSeenByChatId(chatId: String, lastSeenAt: Instant)

    fun updateChatDetails(chatId: String, user: User)

    fun linkIdpId(email: String, idpId: String, lastSeenAt: Instant)

    fun updateEmailByIdpId(idpId: String, email: String, lastSeenAt: Instant)

    fun linkChatId(email: String, user: User)
}
