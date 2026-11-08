package com.github.uncomplexco.sidekick.application.core

import java.time.Instant

data class User(
    val name: String,
    val email: String,
    val idpId: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant? = null,
    val chatId: String? = null,
    val id: Long? = null,
)
