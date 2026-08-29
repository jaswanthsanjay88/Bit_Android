package com.bit.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "McpOAuthClient"

/**
 * MCP OAuth 2.1 authorization client implementing the basic/authorization specification:
 * - RFC 9728 Protected Resource Metadata discovery
 * - RFC 8414 / OIDC Authorization Server metadata discovery
 * - RFC 7591 Dynamic Client Registration (DCR)
 * - PKCE (S256) authorization code flow
 * - RFC 8707 Resource Indicators
 * - Token exchange and refresh
 */
class McpOAuthClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class ProtectedResourceMetadata(
        val resource: String? = null,
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    data class AuthorizationServerMetadata(
        val issuer: String? = null,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
        @SerialName("token_endpoint") val tokenEndpoint: String? = null,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
        @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    )

    @Serializable
    private data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    data class Pkce(val verifier: String, val challenge: String)

    // ── Metadata Discovery ──

    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                probeResourceMetadataUrl(serverUrl)?.let { add(it) }
                addAll(wellKnownPrmUrls(serverUrl))
            }.distinct()
            for (url in candidates) {
                val meta = runCatching { getJson<ProtectedResourceMetadata>(url) }.getOrNull()
                if (meta != null && meta.authorizationServers.isNotEmpty()) {
                    Log.i(TAG, "discoverProtectedResource: found via $url -> ${meta.authorizationServers}")
                    return@withContext meta
                }
            }
            error("Could not discover protected resource metadata for $serverUrl")
        }

    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata =
        withContext(Dispatchers.IO) {
            for (url in wellKnownAsUrls(issuer)) {
                val meta = runCatching { getJson<AuthorizationServerMetadata>(url) }.getOrNull()
                if (meta?.authorizationEndpoint != null && meta.tokenEndpoint != null) {
                    Log.i(TAG, "discoverAuthorizationServer: found via $url")
                    return@withContext meta
                }
            }
            error("Could not discover authorization server metadata for $issuer")
        }

    suspend fun registerClient(
        registrationEndpoint: String,
        clientName: String,
        redirectUri: String,
        scope: String?,
    ): ClientRegistrationResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            ClientRegistrationRequest.serializer(),
            ClientRegistrationRequest(
                clientName = clientName.ifBlank { "BIT Android" },
                redirectUris = listOf(redirectUri),
                scope = scope,
            )
        )
        val request = Request.Builder()
            .url(registrationEndpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        executeAndParse(request, ClientRegistrationResponse.serializer())
    }

    suspend fun exchangeCodeForToken(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        redirectUri: String,
        codeVerifier: String,
        resource: String?,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .apply {
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
                if (!resource.isNullOrBlank()) add("resource", resource)
            }
            .build()

        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .header("Accept", "application/json")
            .build()
        executeAndParse(request, TokenResponse.serializer())
    }

    suspend fun refreshToken(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        resource: String?,
        scope: String?,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .apply {
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
                if (!resource.isNullOrBlank()) add("resource", resource)
                if (!scope.isNullOrBlank()) add("scope", scope)
            }
            .build()

        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(form)
            .header("Accept", "application/json")
            .build()
        executeAndParse(request, TokenResponse.serializer())
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createPkce(): Pkce {
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.UrlSafe.encode(randomBytes).trimEnd('=')
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.UrlSafe.encode(digest).trimEnd('=')
        return Pkce(verifier = verifier, challenge = challenge)
    }

    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        scope: String?,
        state: String,
        pkce: Pkce,
        resource: String?,
    ): String {
        val base = authorizationEndpoint.toHttpUrlOrNull()
            ?: error("Invalid authorization endpoint: $authorizationEndpoint")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .apply {
                if (!scope.isNullOrBlank()) addQueryParameter("scope", scope)
                if (!resource.isNullOrBlank()) addQueryParameter("resource", resource)
            }
            .build()
            .toString()
    }

    // ── Internal Helpers ──

    private suspend fun probeResourceMetadataUrl(serverUrl: String): String? = runCatching {
        val req = Request.Builder()
            .url(serverUrl)
            .get()
            .header("Accept", "application/json, text/event-stream")
            .build()
        val resp = execute(req)
        resp.use {
            if (it.code == 401) {
                parseWwwAuthenticateResourceMetadata(it.header("WWW-Authenticate"))
            } else null
        }
    }.getOrNull()

    private fun parseWwwAuthenticateResourceMetadata(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""resource_metadata\s*=\s*"([^"]+)"""").find(header)
            ?: Regex("""resource_metadata\s*=\s*([^\s,]+)""").find(header)
        return match?.groupValues?.get(1)
    }

    private fun wellKnownPrmUrls(serverUrl: String): List<String> {
        val httpUrl = serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${httpUrl.scheme}://${httpUrl.host}${if (httpUrl.port != 80 && httpUrl.port != 443) ":${httpUrl.port}" else ""}"
        val path = httpUrl.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotBlank() && path != "/") {
                add("$origin/.well-known/oauth-protected-resource$path")
            }
            add("$origin/.well-known/oauth-protected-resource")
        }
    }

    private fun wellKnownAsUrls(issuer: String): List<String> {
        val httpUrl = issuer.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${httpUrl.scheme}://${httpUrl.host}${if (httpUrl.port != 80 && httpUrl.port != 443) ":${httpUrl.port}" else ""}"
        val path = httpUrl.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotBlank() && path != "/") {
                add("$origin/.well-known/oauth-authorization-server$path")
                add("$origin$path/.well-known/openid-configuration")
            }
            add("$origin/.well-known/oauth-authorization-server")
            add("$origin/.well-known/openid-configuration")
        }
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = execute(req)
        return response.use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: $body")
            }
            json.decodeFromString<T>(body)
        }
    }

    private suspend fun <T> executeAndParse(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val response = execute(request)
        return response.use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: $body")
            }
            json.decodeFromString(serializer, body)
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    companion object {
        fun canonicalResource(serverUrl: String): String {
            val u = serverUrl.toHttpUrlOrNull() ?: return serverUrl
            return "${u.scheme}://${u.host}${if (u.port != 80 && u.port != 443) ":${u.port}" else ""}${u.encodedPath}"
        }
    }
}
