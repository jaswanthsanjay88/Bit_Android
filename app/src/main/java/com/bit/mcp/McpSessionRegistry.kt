package com.bit.mcp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "McpSessionRegistry"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

private class McpSession(initialConfig: McpServerConfig) {
    @Volatile
    var config: McpServerConfig = initialConfig

    @Volatile
    var client: McpClient? = null

    val lifecycleMutex = Mutex()
    var reconnectJob: Job? = null
    var reconnectAttempt: Int = 0
}

/**
 * Runtime registry for MCP sessions.
 * Manages connection lifecycle, auto-sync, retry loops, and safe execution.
 */
class McpSessionRegistry(
    private val oauthCoordinator: McpOAuthCoordinator,
    private val statusStore: McpStatusStore,
    private val onToolsDiscovered: suspend (String, List<McpTool>) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    fun getClient(configId: String): McpClient? = sessions[configId]?.client

    fun getStatus(configId: String): Flow<McpStatus> = statusStore.get(configId)

    fun reconcile(configs: List<McpServerConfig>) {
        val activeConfigs = configs
            .filter { it.isEnabled && it.name.isNotBlank() && it.url.isNotBlank() }
            .associateBy { it.id }

        // Remove disconnected
        (sessions.keys - activeConfigs.keys).forEach { configId ->
            val detached = sessions.remove(configId) ?: return@forEach
            oauthCoordinator.forget(configId)
            statusStore.remove(configId)
            scope.launch { closeSession(detached) }
        }

        // Add or update
        activeConfigs.values.forEach { newConfig ->
            val existing = sessions[newConfig.id]
            if (existing == null) {
                val session = McpSession(newConfig)
                if (sessions.putIfAbsent(newConfig.id, session) == null) {
                    scope.launch { connectSession(session) }
                }
                return@forEach
            }

            val mustReconnect = existing.config.url != newConfig.url ||
                existing.config.headers != newConfig.headers ||
                existing.config.commonOptions.oauth?.accessToken != newConfig.commonOptions.oauth?.accessToken

            existing.config = newConfig
            if (mustReconnect) {
                scope.launch { connectSession(existing) }
            }
        }
    }

    suspend fun syncServer(config: McpServerConfig): Result<List<McpTool>> {
        val session = sessions.computeIfAbsent(config.id) { McpSession(config) }
        return connectSession(session)
    }

    suspend fun syncAll(configs: List<McpServerConfig>) {
        configs.filter { it.isEnabled }.forEach { config ->
            val session = sessions.computeIfAbsent(config.id) { McpSession(config) }
            scope.launch { connectSession(session) }
        }
    }

    suspend fun callTool(config: McpServerConfig, toolName: String, args: JSONObject): String {
        val session = sessions[config.id] ?: McpSession(config).also { sessions[config.id] = it }
        val freshConfig = oauthCoordinator.ensureFreshToken(session.config)
        session.config = freshConfig

        val client = session.client ?: McpClient(freshConfig).also { session.client = it }
        return client.callTool(toolName, args)
    }

    private suspend fun connectSession(session: McpSession): Result<List<McpTool>> =
        session.lifecycleMutex.withLock {
            session.reconnectJob?.cancel()
            val config = session.config
            statusStore.update(config.id, McpStatus.Connecting)

            try {
                val client = McpClient(config)
                val tools = client.listTools()
                session.client = client
                session.reconnectAttempt = 0
                statusStore.update(config.id, McpStatus.Connected)
                onToolsDiscovered(config.id, tools)
                Log.i(TAG, "Connected to MCP server '${config.name}' with ${tools.size} tools.")
                Result.success(tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed for MCP server '${config.name}': ${e.message}")
                if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                    statusStore.update(config.id, McpStatus.NeedsAuthorization(config.serverUrl))
                } else {
                    statusStore.update(config.id, McpStatus.Error.from(e))
                    scheduleReconnect(session)
                }
                Result.failure(e)
            }
        }

    private fun scheduleReconnect(session: McpSession) {
        if (!session.config.isEnabled) return
        if (session.reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            statusStore.update(session.config.id, McpStatus.Stopped)
            return
        }

        session.reconnectAttempt++
        val attempt = session.reconnectAttempt
        val delayMs = (BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        statusStore.update(session.config.id, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))

        session.reconnectJob = scope.launch {
            delay(delayMs)
            connectSession(session)
        }
    }

    private suspend fun closeSession(session: McpSession) = session.lifecycleMutex.withLock {
        session.reconnectJob?.cancel()
        session.client = null
        statusStore.update(session.config.id, McpStatus.Stopped)
    }
}
