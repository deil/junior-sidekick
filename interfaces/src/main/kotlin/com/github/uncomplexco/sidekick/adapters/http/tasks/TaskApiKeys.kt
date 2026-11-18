package com.github.uncomplexco.sidekick.adapters.http.tasks

import java.security.MessageDigest
import java.util.HexFormat
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector

internal class TaskApiKeys(properties: TaskApiProperties) : OpaqueTokenIntrospector {
    private val log = LoggerFactory.getLogger(TaskApiKeys::class.java)
    private val keys: List<Key>

    init {
        val keys = mutableListOf<Key>()
        val seenHashes = mutableSetOf<String>()
        val hex = HexFormat.of()
        for ((clientId, client) in properties.clients) {
            require(CLIENT_ID_RE.matches(clientId)) { "Invalid task API client ID" }
            for (hash in client.keyHashes) {
                require(KEY_HASH_RE.matches(hash)) { "Invalid task API key hash" }
                require(seenHashes.add(hash)) { "Duplicate task API key hash" }
                keys += Key(clientId, hex.parseHex(hash))
            }
        }
        this.keys = keys
    }

    override fun introspect(token: String): DefaultOAuth2AuthenticatedPrincipal {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        var clientId: String? = null
        for (key in keys) {
            if (MessageDigest.isEqual(key.hash, digest)) clientId = key.clientId
        }
        if (clientId == null) {
            log.info("Rejected invalid task API key")
            throw BadOpaqueTokenException("Invalid task API key")
        }

        return DefaultOAuth2AuthenticatedPrincipal(clientId, mapOf("sub" to clientId), emptyList())
    }

    private class Key(val clientId: String, val hash: ByteArray)

    private companion object {
        val KEY_HASH_RE = Regex("[0-9a-f]{64}")
        val CLIENT_ID_RE = Regex("[a-zA-Z0-9_-]+")
    }
}
