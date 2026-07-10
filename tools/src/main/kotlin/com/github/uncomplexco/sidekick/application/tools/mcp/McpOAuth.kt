package com.github.uncomplexco.sidekick.application.tools.mcp

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@Component
class McpOAuthService(
    private val agentConfig: AgentConfig,
    private val config: McpToolsConfig,
) {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val refreshSkew = 5.minutes.toJavaDuration()

    fun accessToken(server: McpServerConfig): String? {
        val token = loadToken(server.id) ?: return null
        if (Instant.parse(token.expiresAt).isAfter(Instant.now().plus(refreshSkew))) return token.accessToken
        val refreshToken = token.refreshToken ?: return null

        val client = loadClient(server.id) ?: return null
        val pending = client.pending ?: discover(server).let { metadata ->
            PendingOAuthFlow(
                state = "",
                codeVerifier = "",
                authorizationEndpoint = metadata.authorizationEndpoint,
                tokenEndpoint = metadata.tokenEndpoint,
                createdAt = Instant.now().toString(),
            )
        }

        return runCatching {
            val refreshed = refreshToken(pending.tokenEndpoint, client, refreshToken)
            saveToken(server.id, refreshed)
            refreshed.accessToken
        }.onFailure {
            log.warn("Failed to refresh MCP OAuth token for server {}", server.id, it)
        }.getOrNull()
    }

    suspend fun connect(
        server: McpServerConfig,
        chat: ChatPlatformAdapter,
    ): ConnectMcpResult {
        if (server.auth != "oauth") {
            return ConnectMcpResult(serverId = server.id, auth = "none", started = false, message = "MCP server ${server.id} does not require OAuth")
        }

        val redirectUrl = "${config.oauth.publicBaseUrl.trimEnd('/')}/mcp/oauth/callback"
        val metadata = discover(server)
        val client = loadClient(server.id)?.takeIf { it.redirectUrl == redirectUrl } ?: registerClient(server, metadata, redirectUrl)
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val updatedClient =
            client.copy(
                pending =
                    PendingOAuthFlow(
                        state = state,
                        codeVerifier = verifier,
                        authorizationEndpoint = metadata.authorizationEndpoint,
                        tokenEndpoint = metadata.tokenEndpoint,
                        createdAt = Instant.now().toString(),
                    ),
            )
        saveClient(server.id, updatedClient)

        val authorizationUrl =
            metadata.authorizationEndpoint +
                "?" +
                form(
                    "response_type" to "code",
                    "client_id" to updatedClient.clientId,
                    "redirect_uri" to redirectUrl,
                    "code_challenge" to codeChallenge(verifier),
                    "code_challenge_method" to "S256",
                    "state" to state,
                )
        chat.postReply(ChatReply("Connect ${server.id} MCP server: $authorizationUrl"))

        return ConnectMcpResult(serverId = server.id, auth = "oauth", started = true, message = "OAuth authorization link sent")
    }

    fun completeCallback(
        state: String,
        code: String,
    ): String {
        val server = config.servers.firstNotNullOfOrNull { server ->
            loadClient(server.id)?.takeIf { it.pending?.state == state }?.let { server to it }
        } ?: error("Unknown or expired MCP OAuth state")
        val (serverConfig, client) = server
        val pending = client.pending ?: error("Missing pending MCP OAuth flow")

        val token = exchangeCode(pending.tokenEndpoint, client, code, pending.codeVerifier)
        saveToken(serverConfig.id, token)
        saveClient(serverConfig.id, client.copy(pending = null))
        return serverConfig.id
    }

    private fun discover(server: McpServerConfig): OAuthMetadata {
        val serverUri = URI.create(server.url)
        val origin = "${serverUri.scheme}://${serverUri.authority}"
        val protectedResource =
            runCatching {
                val request = HttpRequest.newBuilder(URI.create("$origin/.well-known/oauth-protected-resource")).GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) error("OAuth HTTP ${response.statusCode()} from ${request.uri()}: ${response.body()}")
                json.parseToJsonElement(response.body()).jsonObject
            }.getOrNull()
        val authorizationServer = protectedResource
            ?.get("authorization_servers")
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.contentOrNull
            ?: origin
        val metadataUrl = "${authorizationServer.trimEnd('/')}/.well-known/oauth-authorization-server"
        val metadataRequest = HttpRequest.newBuilder(URI.create(metadataUrl)).GET().build()
        val metadataResponse = http.send(metadataRequest, HttpResponse.BodyHandlers.ofString())
        if (metadataResponse.statusCode() !in 200..299) {
            error("OAuth HTTP ${metadataResponse.statusCode()} from $metadataUrl: ${metadataResponse.body()}")
        }
        val metadata = json.parseToJsonElement(metadataResponse.body()).jsonObject
        return OAuthMetadata(
            authorizationEndpoint = metadata["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: error("Missing OAuth metadata field: authorization_endpoint"),
            tokenEndpoint = metadata["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: error("Missing OAuth metadata field: token_endpoint"),
            registrationEndpoint = metadata["registration_endpoint"]?.jsonPrimitive?.contentOrNull ?: error("Missing OAuth metadata field: registration_endpoint"),
        )
    }

    private fun registerClient(
        server: McpServerConfig,
        metadata: OAuthMetadata,
        redirectUrl: String,
    ): OAuthClientState {
        val body =
            JsonObject(
                mapOf(
                    "client_name" to JsonPrimitive("${agentConfig.name} MCP Client"),
                    "redirect_uris" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(redirectUrl))),
                    "grant_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))),
                    "response_types" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))),
                    "token_endpoint_auth_method" to JsonPrimitive("client_secret_post"),
                ),
            )
        val request =
            HttpRequest.newBuilder(URI.create(metadata.registrationEndpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        val httpResponse = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() !in 200..299) {
            error("OAuth HTTP ${httpResponse.statusCode()} from ${metadata.registrationEndpoint}: ${httpResponse.body()}")
        }
        val response = httpResponse.body()
        val registered = json.parseToJsonElement(response).jsonObject
        return OAuthClientState(
            serverId = server.id,
            redirectUrl = redirectUrl,
            clientId = registered["client_id"]?.jsonPrimitive?.contentOrNull ?: error("Missing OAuth client registration field: client_id"),
            clientSecret = registered["client_secret"]?.jsonPrimitive?.contentOrNull,
            clientIdIssuedAt = registered["client_id_issued_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            clientSecretExpiresAt = registered["client_secret_expires_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        ).also { saveClient(server.id, it) }
    }

    private fun exchangeCode(
        tokenEndpoint: String,
        client: OAuthClientState,
        code: String,
        verifier: String,
    ): OAuthTokenState =
        tokenRequest(
            tokenEndpoint,
            client,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to client.redirectUrl,
            "code_verifier" to verifier,
        )

    private fun refreshToken(
        tokenEndpoint: String,
        client: OAuthClientState,
        refreshToken: String,
    ): OAuthTokenState =
        tokenRequest(
            tokenEndpoint,
            client,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )

    private fun tokenRequest(
        tokenEndpoint: String,
        client: OAuthClientState,
        vararg fields: Pair<String, String>,
    ): OAuthTokenState {
        val requestFields = mutableListOf("client_id" to client.clientId)
        client.clientSecret?.let { requestFields += "client_secret" to it }
        requestFields += fields
        val request =
            HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(*requestFields.toTypedArray())))
                .build()
        val httpResponse = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() !in 200..299) {
            error("OAuth HTTP ${httpResponse.statusCode()} from $tokenEndpoint: ${httpResponse.body()}")
        }
        val response = httpResponse.body()
        val token = json.parseToJsonElement(response).jsonObject
        val expiresIn = token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600
        return OAuthTokenState(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull ?: error("Missing OAuth token field: access_token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull ?: loadToken(client.serverId)?.refreshToken,
            tokenType = token["token_type"]?.jsonPrimitive?.contentOrNull ?: "Bearer",
            expiresAt = Instant.now().plusSeconds(expiresIn).toString(),
            scope = token["scope"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun mcpDir(serverId: String): Path {
        val dir = agentConfig.stateDirectoryPath().resolve("mcp").resolve(serverId)
        Files.createDirectories(dir)
        return dir
    }

    private fun loadClient(serverId: String): OAuthClientState? {
        val path = mcpDir(serverId).resolve("oauth-client.json")
        if (!path.exists()) return null
        return json.decodeFromString<OAuthClientState>(Files.readString(path))
    }

    private fun saveClient(
        serverId: String,
        client: OAuthClientState,
    ) = Files.writeString(mcpDir(serverId).resolve("oauth-client.json"), json.encodeToString(client))

    private fun loadToken(serverId: String): OAuthTokenState? {
        val path = mcpDir(serverId).resolve("oauth-token.json")
        if (!path.exists()) return null
        return json.decodeFromString<OAuthTokenState>(Files.readString(path))
    }

    private fun saveToken(
        serverId: String,
        token: OAuthTokenState,
    ) = Files.writeString(mcpDir(serverId).resolve("oauth-token.json"), json.encodeToString(token))

    private fun form(vararg fields: Pair<String, String>): String {
        return fields.joinToString("&") { (name, value) ->
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8)
            "$encodedName=$encodedValue"
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(McpOAuthService::class.java)
    }
}

data class ConnectMcpResult(
    val serverId: String,
    val auth: String,
    val started: Boolean,
    val message: String,
)

private data class OAuthMetadata(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String,
)

@Serializable
data class OAuthClientState(
    val serverId: String,
    val redirectUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val clientIdIssuedAt: Long? = null,
    val clientSecretExpiresAt: Long? = null,
    val pending: PendingOAuthFlow? = null,
)

@Serializable
data class PendingOAuthFlow(
    val state: String,
    val codeVerifier: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val createdAt: String,
)

@Serializable
data class OAuthTokenState(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAt: String,
    val scope: String? = null,
)
