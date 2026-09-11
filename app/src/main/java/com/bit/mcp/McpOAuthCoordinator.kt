package com.bit.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

private const val TAG = "McpOAuthCoordinator"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

/**
 * Coordinates OAuth 2.1 authorization, code redirects, token storage, and background token refresh.
 */
class McpOAuthCoordinator(
    private val oauthClient: McpOAuthClient = McpOAuthClient(),
    private val updateStatus: (String, McpStatus) -> Unit,
    private val onOAuthUpdated: suspend (String, McpOAuthState?) -> Unit,
    private val getCurrentConfig: (String) -> McpServerConfig?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val authorizationJobs = ConcurrentHashMap<String, Job>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = scope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth authorization failed for ${config.name}", e)
                updateStatus(config.id, McpStatus.Error.from(e, fallbackMessage = "OAuth authorization failed"))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: String) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization())
    }

    fun forget(configId: String) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        onOAuthUpdated(config.id, null)
        return config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * Serializes token refresh per server ID. Validates expiry with leeway and refreshes with AS.
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = getCurrentConfig(configInput.id) ?: configInput
            val oauth = config.commonOptions.oauth ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config

            val expired = oauth.expiresAt > 0 &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!oauth.accessToken.isNullOrBlank() && !expired) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                val token = oauthClient.refreshToken(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = oauth.clientSecret,
                    refreshToken = oauth.refreshToken,
                    resource = McpOAuthClient.canonicalResource(config.serverUrl),
                    scope = oauth.scope,
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                onOAuthUpdated(config.id, updated)
                config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
            }.getOrElse {
                Log.w(TAG, "Token refresh failed for ${config.name}: ${it.message}")
                config
            }
        }
    }

    private suspend fun authorize(config: McpServerConfig, appContext: Context) {
        val serverUrl = config.serverUrl
        val existingOAuth = config.commonOptions.oauth
        var authEndpoint = existingOAuth?.authorizationEndpoint
        var tokenEndpoint = existingOAuth?.tokenEndpoint
        var regEndpoint = existingOAuth?.registrationEndpoint
        var clientId = existingOAuth?.clientId
        var clientSecret = existingOAuth?.clientSecret
        var scope = existingOAuth?.scope

        // 1. Metadata Discovery
        if (authEndpoint.isNullOrBlank() || tokenEndpoint.isNullOrBlank()) {
            val prm = runCatching { oauthClient.discoverProtectedResource(serverUrl) }.getOrNull()
            val asIssuer = prm?.authorizationServers?.firstOrNull() ?: serverUrl
            val asMeta = oauthClient.discoverAuthorizationServer(asIssuer)

            authEndpoint = authEndpoint ?: asMeta.authorizationEndpoint
                ?: error("Authorization server metadata missing authorization_endpoint")
            tokenEndpoint = tokenEndpoint ?: asMeta.tokenEndpoint
                ?: error("Authorization server metadata missing token_endpoint")
            regEndpoint = regEndpoint ?: asMeta.registrationEndpoint
            if (scope.isNullOrBlank()) {
                scope = prm?.scopesSupported?.joinToString(" ")
                    ?: asMeta.scopesSupported?.joinToString(" ")
            }
        }

        // 2. Dynamic Client Registration (RFC 7591)
        val redirectUri = "bit://oauth/callback"
        if (clientId.isNullOrBlank()) {
            if (!regEndpoint.isNullOrBlank()) {
                val reg = oauthClient.registerClient(
                    registrationEndpoint = regEndpoint,
                    clientName = config.name.ifBlank { "BIT Android" },
                    redirectUri = redirectUri,
                    scope = scope,
                )
                clientId = reg.clientId
                clientSecret = clientSecret ?: reg.clientSecret
            } else {
                error("No client_id provided and authorization server does not support dynamic client registration")
            }
        }

        // 3. Construct PKCE & State
        val pkce = oauthClient.createPkce()
        val stateToken = UUID.randomUUID().toString()
        val canonicalRes = McpOAuthClient.canonicalResource(serverUrl)

        val authUrl = oauthClient.buildAuthorizationUrl(
            authorizationEndpoint = authEndpoint,
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope,
            state = stateToken,
            pkce = pkce,
            resource = canonicalRes,
        )

        // 4. Launch Browser Intent
        withContext(Dispatchers.Main) {
            val uri = Uri.parse(authUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }

        // 5. Await Callback Result
        val result = withTimeoutOrNull(OAUTH_CALLBACK_TIMEOUT) {
            McpOAuthCallbackHub.results
                .filterIsInstance<McpOAuthResult>()
                .first {
                    when (it) {
                        is McpOAuthResult.Success -> it.state == stateToken
                        is McpOAuthResult.Error -> it.state == stateToken || it.state == null
                    }
                }
        } ?: error("OAuth authorization timed out (5 minutes)")

        val code = when (result) {
            is McpOAuthResult.Success -> result.code
            is McpOAuthResult.Error -> error("OAuth authorization error: ${result.error} (${result.errorDescription.orEmpty()})")
        }

        // 6. Token Exchange
        val token = oauthClient.exchangeCodeForToken(
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            clientSecret = clientSecret,
            code = code,
            redirectUri = redirectUri,
            codeVerifier = pkce.verifier,
            resource = canonicalRes,
        )

        val finalOAuth = McpOAuthState(
            enabled = true,
            clientId = clientId,
            clientSecret = clientSecret,
            authorizationEndpoint = authEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = regEndpoint,
            scope = token.scope ?: scope,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = computeExpiry(token.expiresIn),
        )

        onOAuthUpdated(config.id, finalOAuth)
        updateStatus(config.id, McpStatus.Connected)
        Log.i(TAG, "OAuth authorization completed successfully for ${config.name}")
    }

    private fun computeExpiry(expiresInSec: Long?): Long {
        if (expiresInSec == null || expiresInSec <= 0L) return 0L
        return System.currentTimeMillis() + expiresInSec * 1000L
    }
}
