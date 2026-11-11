package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.core.User
import com.github.uncomplexco.sidekick.ports.UsersRepository
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SqliteUsersRepository(private val sidekickJdbcClient: JdbcClient) : UsersRepository {
    override fun add(user: User) {
        sidekickJdbcClient
            .sql(
                """
                INSERT INTO users (name, email, idp_id, chat_id, created_at, last_seen_at,
                    chat_workspace_id, is_bot, is_guest, chat_details_updated_at)
                VALUES (:name, :email, :idp_id, :chat_id, :created_at, :last_seen_at,
                    :chat_workspace_id, :is_bot, :is_guest, :chat_details_updated_at)
                """
                    .trimIndent()
            )
            .param("name", user.name)
            .param("email", user.email)
            .param("idp_id", user.idpId)
            .param("chat_id", user.chatId)
            .param("created_at", user.createdAt.toString())
            .param("last_seen_at", user.lastSeenAt?.toString())
            .param("chat_workspace_id", user.chatWorkspaceId)
            .param("is_bot", user.isBot)
            .param("is_guest", user.isGuest)
            .param("chat_details_updated_at", user.chatDetailsUpdatedAt?.toString())
            .update()
    }

    override fun findAllByIdpId(idpId: String): List<User> =
        sidekickJdbcClient
            .sql("SELECT * FROM users WHERE idp_id = :idp_id ORDER BY id")
            .param("idp_id", idpId)
            .query { row, _ -> row.toUser() }
            .list()

    override fun findByChatId(chatId: String): User? =
        sidekickJdbcClient
            .sql("SELECT * FROM users WHERE chat_id = :chat_id ORDER BY id LIMIT 1")
            .param("chat_id", chatId)
            .query { row, _ -> row.toUser() }
            .optional()
            .orElse(null)

    override fun updateLastSeenByChatId(chatId: String, lastSeenAt: Instant) {
        sidekickJdbcClient
            .sql("UPDATE users SET last_seen_at = :last_seen_at WHERE chat_id = :chat_id")
            .param("last_seen_at", lastSeenAt.toString())
            .param("chat_id", chatId)
            .update()
    }

    override fun findByEmail(email: String): User? =
        sidekickJdbcClient
            .sql("SELECT * FROM users WHERE email = :email")
            .param("email", email)
            .query { row, _ -> row.toUser() }
            .optional()
            .orElse(null)

    override fun updateChatDetails(chatId: String, user: User) {
        sidekickJdbcClient
            .sql(
                """
                UPDATE users SET last_seen_at = :last_seen_at,
                    chat_workspace_id = :chat_workspace_id, is_bot = :is_bot, is_guest = :is_guest,
                    chat_details_updated_at = :chat_details_updated_at
                WHERE chat_id = :chat_id
                """
                    .trimIndent()
            )
            .param("chat_id", chatId)
            .param("last_seen_at", user.lastSeenAt?.toString())
            .param("chat_workspace_id", user.chatWorkspaceId)
            .param("is_bot", user.isBot)
            .param("is_guest", user.isGuest)
            .param("chat_details_updated_at", user.chatDetailsUpdatedAt?.toString())
            .update()
    }

    override fun linkIdpId(email: String, idpId: String, lastSeenAt: Instant) {
        sidekickJdbcClient
            .sql(
                "UPDATE users SET idp_id = :idp_id, last_seen_at = :last_seen_at WHERE email = :email"
            )
            .param("last_seen_at", lastSeenAt.toString())
            .param("idp_id", idpId)
            .param("email", email)
            .update()
    }

    override fun linkChatId(email: String, user: User) {
        sidekickJdbcClient
            .sql(
                """
                UPDATE users SET chat_id = :chat_id, last_seen_at = :last_seen_at,
                    chat_workspace_id = :chat_workspace_id, is_bot = :is_bot, is_guest = :is_guest,
                    chat_details_updated_at = :chat_details_updated_at
                WHERE email = :email
                """
                    .trimIndent()
            )
            .param("last_seen_at", user.lastSeenAt?.toString())
            .param("chat_id", user.chatId)
            .param("chat_workspace_id", user.chatWorkspaceId)
            .param("is_bot", user.isBot)
            .param("is_guest", user.isGuest)
            .param("chat_details_updated_at", user.chatDetailsUpdatedAt?.toString())
            .param("email", email)
            .update()
    }

    override fun updateEmailByIdpId(idpId: String, email: String, lastSeenAt: Instant) {
        sidekickJdbcClient
            .sql(
                "UPDATE users SET email = :email, last_seen_at = :last_seen_at WHERE idp_id = :idp_id"
            )
            .param("email", email)
            .param("last_seen_at", lastSeenAt.toString())
            .param("idp_id", idpId)
            .update()
    }

    private fun ResultSet.toUser() =
        User(
            id = getLong("id"),
            name = getString("name"),
            email = getString("email"),
            idpId = getString("idp_id"),
            chatId = getString("chat_id"),
            createdAt = Instant.parse(getString("created_at")),
            lastSeenAt = getString("last_seen_at")?.let(Instant::parse),
            chatWorkspaceId = getString("chat_workspace_id"),
            isBot = getObject("is_bot")?.let { getBoolean("is_bot") },
            isGuest = getObject("is_guest")?.let { getBoolean("is_guest") },
            chatDetailsUpdatedAt = getString("chat_details_updated_at")?.let(Instant::parse),
        )
}
