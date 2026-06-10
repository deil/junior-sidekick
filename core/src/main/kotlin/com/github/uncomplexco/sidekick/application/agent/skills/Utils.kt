package com.github.uncomplexco.sidekick.application.agent.skills

import java.security.MessageDigest

fun cleanYamlScalar(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")

fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
