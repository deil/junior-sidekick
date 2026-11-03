package com.github.uncomplexco.sidekick.application.tools.mcp

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationRequest
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException

@Component
class McpOAuthService(
    private val agentConfig: AgentConfig,
    private val config: McpToolsConfig,
) {
    private val http = HttpClient.newHttpClient()
    private val discovery = McpMetadataDiscoveryService()
    private val registration = DynamicClientRegistrationService()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val refreshSkew = 5.minutes.toJavaDuration()

    fun accessToken(server: McpServerConfig): String? {
        val token = loadToken(server.id) ?: return null
        if (Instant.parse(token.expiresAt).isAfter(Instant.now().plus(refreshSkew))) {
            return token.accessToken
        }
        val refreshToken = token.refreshToken ?: return null

        val client = loadClient(server.id) ?: return null
        val pending =
            client.pending
                ?: discover(server).let { metadata ->
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
            }
            .onFailure {
                log.warn("Failed to refresh MCP OAuth token for server {}", server.id, it)
            }
            .getOrNull()
    }

    suspend fun connect(
        server: McpServerConfig,
        responseDelivery: ResponseDelivery,
    ): ConnectMcpResult {
        if (server.auth != "oauth") {
            return ConnectMcpResult(
                serverId = server.id,
                auth = "none",
                started = false,
                message = "MCP server ${server.id} does not require OAuth",
            )
        }

        val redirectUrl = "${config.oauth.publicBaseUrl.trimEnd('/')}/mcp/oauth/callback"
        val metadata = discover(server)
        val client =
            loadClient(server.id)?.takeIf {
                it.redirectUrl == redirectUrl && it.issuer == metadata.issuer
            } ?: registerClient(server, metadata, redirectUrl)
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val updatedClient =
            client.copy(
                resource = metadata.resource,
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

        val authorizationFields =
            mutableListOf(
                "response_type" to "code",
                "client_id" to updatedClient.clientId,
                "redirect_uri" to redirectUrl,
                "code_challenge" to codeChallenge(verifier),
                "code_challenge_method" to "S256",
                "state" to state,
                "resource" to metadata.resource,
            )
        metadata.scope?.let { authorizationFields += "scope" to it }
        val authorizationUrl =
            metadata.authorizationEndpoint +
                (if (URI.create(metadata.authorizationEndpoint).rawQuery == null) "?" else "&") +
                form(*authorizationFields.toTypedArray())
        responseDelivery.deliver(
            AgentResponse("Connect ${server.id} MCP server: $authorizationUrl")
        )

        return ConnectMcpResult(
            serverId = server.id,
            auth = "oauth",
            started = true,
            message = "OAuth authorization link sent",
        )
    }

    fun completeCallback(
        state: String,
        code: String,
    ): String {
        val server =
            config.servers.firstNotNullOfOrNull { server ->
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
        log.info("Discovering MCP OAuth metadata for server {}", server.id)
        val challenge =
            try {
                discovery.getWwwAuthenticateParameters(server.url)
            } catch (_: HttpClientErrorException.MethodNotAllowed) {
                log.info(
                    "MCP OAuth challenge probe does not support POST for server {}; using metadata discovery",
                    server.id,
                )
                null
            }
        val protectedResource =
            if (challenge == null) {
                discovery.getMcpMetadata(server.url, null).protectedResourceMetadata()
            } else {
                discovery.getProtectedResourceMetadata(
                    server.url,
                    listOf(challenge.resourceMetadata),
                )
            }
        val issuer =
            protectedResource.authorizationServers()?.firstOrNull()
                ?: error("MCP server ${server.id} advertises no authorization server")
        val metadata =
            ClientRegistrations.fromIssuerLocation(issuer)
                .clientId(server.id)
                .build()
                .providerDetails
        val capabilities = metadata.configurationMetadata
        check(
            (capabilities["code_challenge_methods_supported"] as? List<*>)?.contains("S256") == true
        ) {
            "MCP authorization server does not advertise PKCE S256 support"
        }
        val supportedMethods =
            capabilities["token_endpoint_auth_methods_supported"] as? List<*>
                ?: listOf(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.value)
        val authenticationMethod =
            listOf(
                    ClientAuthenticationMethod.CLIENT_SECRET_POST,
                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC,
                    ClientAuthenticationMethod.NONE,
                )
                .firstOrNull { it.value in supportedMethods }
                ?: error(
                    "MCP authorization server advertises no supported client authentication method"
                )
        return OAuthMetadata(
            issuer = issuer,
            authorizationEndpoint =
                requireNotNull(metadata.authorizationUri) {
                    "MCP authorization server advertises no authorization endpoint"
                },
            tokenEndpoint = metadata.tokenUri,
            resource = protectedResource.resource(),
            scope = challenge?.scope ?: protectedResource.scopesSupported()?.joinToString(" "),
            authenticationMethod = authenticationMethod,
        )
    }

    private fun registerClient(
        server: McpServerConfig,
        metadata: OAuthMetadata,
        redirectUrl: String,
    ): OAuthClientState {
        val request =
            DynamicClientRegistrationRequest.builder()
                .clientName("${agentConfig.name} MCP Client")
                .redirectUris(listOf(redirectUrl))
                .grantTypes(
                    listOf(
                        AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN,
                    )
                )
                .tokenEndpointAuthMethod(metadata.authenticationMethod)
        log.info(
            "Registering MCP OAuth client for server {} with issuer {}",
            server.id,
            metadata.issuer,
        )
        val registered = registration.register(request.build(), metadata.issuer)
        return OAuthClientState(
                serverId = server.id,
                redirectUrl = redirectUrl,
                clientId = registered.clientId(),
                clientSecret = registered.clientSecret(),
                clientIdIssuedAt = registered.clientIdIssuedAt()?.epochSecond,
                clientSecretExpiresAt = registered.clientSecretExpiresAt()?.epochSecond,
                issuer = metadata.issuer,
                resource = metadata.resource,
                tokenEndpointAuthMethod =
                    registered.tokenEndpointAuthMethod() ?: metadata.authenticationMethod.value,
            )
            .also { saveClient(server.id, it) }
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
        if (client.tokenEndpointAuthMethod == ClientAuthenticationMethod.CLIENT_SECRET_POST.value) {
            client.clientSecret?.let { requestFields += "client_secret" to it }
        }
        client.resource?.let { requestFields += "resource" to it }
        requestFields += fields
        val requestBuilder = HttpRequest.newBuilder(URI.create(tokenEndpoint))
        if (
            client.tokenEndpointAuthMethod == ClientAuthenticationMethod.CLIENT_SECRET_BASIC.value
        ) {
            val credentials =
                listOf(client.clientId, requireNotNull(client.clientSecret)).joinToString(":") {
                    URLEncoder.encode(it, StandardCharsets.UTF_8)
                }
            requestBuilder.header(
                "Authorization",
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.US_ASCII))}",
            )
        }
        val request =
            requestBuilder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(*requestFields.toTypedArray())))
                .build()
        val httpResponse = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (httpResponse.statusCode() !in 200..299) {
            error(
                "OAuth HTTP ${httpResponse.statusCode()} from $tokenEndpoint: ${httpResponse.body()}"
            )
        }
        val response = httpResponse.body()
        val token = json.parseToJsonElement(response).jsonObject
        val expiresIn = token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600
        return OAuthTokenState(
            accessToken =
                token["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("Missing OAuth token field: access_token"),
            refreshToken =
                token["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?: loadToken(client.serverId)?.refreshToken,
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
    ) =
        Files.writeString(
            mcpDir(serverId).resolve("oauth-client.json"),
            json.encodeToString(client),
        )

    private fun loadToken(serverId: String): OAuthTokenState? {
        val path = mcpDir(serverId).resolve("oauth-token.json")
        if (!path.exists()) return null
        return json.decodeFromString<OAuthTokenState>(Files.readString(path))
    }

    private fun saveToken(
        serverId: String,
        token: OAuthTokenState,
    ) = Files.writeString(mcpDir(serverId).resolve("oauth-token.json"), json.encodeToString(token))

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (name, value) ->
            val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8)
            "$encodedName=$encodedValue"
        }

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun codeChallenge(verifier: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
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
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String,
    val scope: String?,
    val authenticationMethod: ClientAuthenticationMethod,
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
    val issuer: String? = null,
    val resource: String? = null,
    val tokenEndpointAuthMethod: String = "client_secret_post",
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
