package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.core.UsersService
import com.github.uncomplexco.sidekick.application.core.User
import com.github.uncomplexco.sidekick.ports.UsersRepository
import java.time.Instant

class NoOpUsersRepository : UsersRepository {
    override fun add(user: User) = Unit

    override fun findAllByIdpId(idpId: String): List<User> = emptyList()

    override fun findByChatId(chatId: String): User? = null

    override fun findByEmail(email: String): User? = null

    override fun updateLastSeenByChatId(chatId: String, lastSeenAt: Instant) = Unit

    override fun linkIdpId(email: String, idpId: String, lastSeenAt: Instant) = Unit

    override fun updateEmailByIdpId(idpId: String, email: String, lastSeenAt: Instant) = Unit

    override fun linkChatId(email: String, chatId: String, lastSeenAt: Instant) = Unit
}

private class KnownChatUsersRepository : UsersRepository by NoOpUsersRepository() {
    override fun findByChatId(chatId: String): User =
        User("Test User", "user@example.com", null, Instant.EPOCH, chatId = chatId)
}

fun usersService(): UsersService = UsersService(KnownChatUsersRepository())
