package com.github.uncomplexco.sidekick.application.utils

import java.security.MessageDigest

fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
