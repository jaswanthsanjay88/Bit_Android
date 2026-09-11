package com.bit.mcp

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Dynamic MCP Server Manager with Disk Persistence and OAuth 2.1 lifecycle coordination.
 * Tools and server configurations are dynamically introspected from external MCP servers
 * and persisted to disk so servers and tool preferences never disappear across app restarts.
 */
@Singleton
class McpManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("bit_mcp_servers_store_v2", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _servers = MutableStateFlow<List<McpServerConfig>>(loadSavedServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val statusStore = McpStatusStore()

    private val oauthClient = McpOAuthClient()

    private val oauthCoordinator = McpOAuthCoordinator(
        oauthClient = oauthClient,
        updateStatus = statusStore::update,
        onOAuthUpdated = { serverId, oauthState ->
            updateServerOAuth(serverId, oauthState)
        },
        getCurrentConfig = { serverId ->
            _servers.value.find { it.id == serverId }
        },
        scope = scope,
    )

    private val sessionRegistry = McpSessionRegistry(
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
        onToolsDiscovered = { serverId, newTools ->
            updateServerTools(serverId, newTools)
        },
        scope = scope,
    )

    val syncingStatus: StateFlow<Map<String, McpStatus>>
        get() = statusStore.status

    init {
        instance = this
        sessionRegistry.reconcile(_servers.value)
        scope.launch {
            syncAll()
        }
    }

    companion object {
        private const val TAG = "McpManager"

        @Volatile
        private var instance: McpManager? = null

        fun getInstance(context: Context): McpManager {
            return instance ?: synchronized(this) {
                instance ?: McpManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadSavedServers(): List<McpServerConfig> {
        val rawJson = prefs.getString("servers_json_v2", null)
        val loaded = if (!rawJson.isNullOrBlank()) {
            runCatching {
                json.decodeFromString<List<McpServerConfig>>(rawJson)
            }.getOrElse {
                Log.w(TAG, "Failed to decode v2 servers json, attempting v1 migration: ${it.message}")
                migrateV1Servers()
            }
        } else {
            migrateV1Servers()
        }

        // Guarantee unique IDs across all loaded servers
        val seenIds = mutableSetOf<String>()
        val uniqueList = loaded.map { server ->
            if (seenIds.add(server.id)) {
                server
            } else {
                server.clone(id = UUID.randomUUID().toString())
            }
        }
        if (uniqueList != loaded) {
            persistServers(uniqueList)
        }
        return uniqueList
    }

    private fun migrateV1Servers(): List<McpServerConfig> {
        val oldJson = prefs.getString("servers_json", null)
            ?: context.getSharedPreferences("bit_mcp_servers_store", Context.MODE_PRIVATE)
                .getString("servers_json", null)
            ?: return emptyList()

        return try {
            val arr = JSONArray(oldJson)
            val list = mutableListOf<McpServerConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val name = obj.optString("name", "MCP Server")
                val url = obj.optString("url", "")
                val isEnabled = obj.optBoolean("isEnabled", true)

                val headersObj = obj.optJSONObject("headers") ?: JSONObject()
                val headersList = mutableListOf<Pair<String, String>>()
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headersList.add(k to headersObj.optString(k))
                }

                val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
                val toolsList = mutableListOf<McpTool>()
                for (j in 0 until toolsArr.length()) {
                    val t = toolsArr.getJSONObject(j)
                    toolsList.add(
                        McpTool(
                            name = t.optString("name"),
                            description = t.optString("description"),
                            enable = t.optBoolean("isEnabled", true),
                            inputSchemaJson = t.optString("inputSchemaJson", "{}"),
                            needsApproval = t.optBoolean("needsApproval", false)
                        )
                    )
                }

                list.add(
                    McpServerConfig.StreamableHTTPServer(
                        id = id,
                        commonOptions = McpCommonOptions(
                            enable = isEnabled,
                            name = name,
                            headers = headersList,
                            tools = toolsList,
                            oauth = null
                        ),
                        url = url
                    )
                )
            }
            persistServers(list)
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating old MCP servers", e)
            emptyList()
        }
    }

    private fun persistServers(serversList: List<McpServerConfig>) {
        try {
            val serialized = json.encodeToString(serversList)
            prefs.edit().putString("servers_json_v2", serialized).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting MCP servers", e)
        }
    }

    fun addServer(
        name: String,
        url: String,
        isStreamableHttp: Boolean = true,
        headers: List<Pair<String, String>> = emptyList(),
        oauth: McpOAuthState? = null
    ): McpServerConfig {
        val newConfig = if (isStreamableHttp) {
            McpServerConfig.StreamableHTTPServer(
                commonOptions = McpCommonOptions(
                    enable = true,
                    name = name.trim(),
                    headers = headers,
                    tools = emptyList(),
                    oauth = oauth
                ),
                url = url.trim()
            )
        } else {
            McpServerConfig.SseTransportServer(
                commonOptions = McpCommonOptions(
                    enable = true,
                    name = name.trim(),
                    headers = headers,
                    tools = emptyList(),
                    oauth = oauth
                ),
                url = url.trim()
            )
        }

        val updated = _servers.value + newConfig
        _servers.value = updated
        persistServers(updated)
        sessionRegistry.reconcile(updated)
        scope.launch {
            syncServer(newConfig.id)
        }
        return newConfig
    }

    fun addServers(newServers: List<McpServerConfig>) {
        if (newServers.isEmpty()) return
        val current = _servers.value.toMutableList()
        val currentIds = current.map { it.id }.toMutableSet()
        val sanitized = newServers.map { server ->
            if (currentIds.add(server.id)) {
                server
            } else {
                server.clone(id = UUID.randomUUID().toString())
            }
        }
        val updated = current + sanitized
        _servers.value = updated
        persistServers(updated)
        sessionRegistry.reconcile(updated)
        scope.launch {
            sessionRegistry.syncAll(sanitized)
        }
    }

    fun updateServer(config: McpServerConfig) {
        val updated = _servers.value.map {
            if (it.id == config.id) config else it
        }
        _servers.value = updated
        persistServers(updated)
        sessionRegistry.reconcile(updated)
    }

    fun removeServer(serverId: String) {
        val updated = _servers.value.filter { it.id != serverId }
        _servers.value = updated
        persistServers(updated)
        sessionRegistry.reconcile(updated)
    }

    fun toggleServer(serverId: String, isEnabled: Boolean) {
        val updated = _servers.value.map {
            if (it.id == serverId) it.clone(commonOptions = it.commonOptions.copy(enable = isEnabled)) else it
        }
        _servers.value = updated
        persistServers(updated)
        sessionRegistry.reconcile(updated)
    }

    fun toggleTool(serverId: String, toolName: String, isEnabled: Boolean) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                val updatedTools = server.commonOptions.tools.map { tool ->
                    if (tool.name == toolName) tool.copy(enable = isEnabled) else tool
                }
                server.clone(commonOptions = server.commonOptions.copy(tools = updatedTools))
            } else server
        }
        _servers.value = updated
        persistServers(updated)
    }

    fun toggleToolNeedsApproval(serverId: String, toolName: String, needsApproval: Boolean) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                val updatedTools = server.commonOptions.tools.map { tool ->
                    if (tool.name == toolName) tool.copy(needsApproval = needsApproval) else tool
                }
                server.clone(commonOptions = server.commonOptions.copy(tools = updatedTools))
            } else server
        }
        _servers.value = updated
        persistServers(updated)
    }

    fun reorderServers(fromIndex: Int, toIndex: Int) {
        val list = _servers.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _servers.value = list
            persistServers(list)
        }
    }

    fun setOrderedServers(orderedList: List<McpServerConfig>) {
        _servers.value = orderedList
        persistServers(orderedList)
    }

    suspend fun syncServer(serverId: String): Result<List<McpTool>> {
        val server = _servers.value.find { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Server not found: $serverId"))
        return sessionRegistry.syncServer(server)
    }

    suspend fun syncAll() {
        sessionRegistry.syncAll(_servers.value)
    }

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val fresh = oauthCoordinator.clearAuthorization(config)
        updateServer(fresh)
    }

    suspend fun executeTool(serverId: String, toolName: String, args: JSONObject): String {
        val server = _servers.value.find { it.id == serverId }
            ?: throw IllegalArgumentException("Server not found: $serverId")
        return sessionRegistry.callTool(server, toolName, args)
    }

    private fun updateServerTools(serverId: String, newTools: List<McpTool>) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                val existingTools = server.commonOptions.tools
                val merged = mutableListOf<McpTool>()

                newTools.forEach { fetched ->
                    val existing = existingTools.find { it.name == fetched.name }
                    merged.add(
                        if (existing != null) {
                            existing.copy(
                                description = fetched.description,
                                inputSchemaJson = fetched.inputSchemaJson
                            )
                        } else {
                            fetched
                        }
                    )
                }

                server.clone(commonOptions = server.commonOptions.copy(tools = merged))
            } else server
        }
        _servers.value = updated
        persistServers(updated)
    }

    private fun updateServerOAuth(serverId: String, oauthState: McpOAuthState?) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                server.clone(commonOptions = server.commonOptions.copy(oauth = oauthState))
            } else server
        }
        _servers.value = updated
        persistServers(updated)
    }

    /**
     * Generates a structured markdown catalog of all active MCP servers and their capabilities
     * following the Model Context Protocol specification.
     */
    fun getMcpCatalogPrompt(): String {
        val enabledServers = _servers.value.filter { it.isEnabled }
        val serversWithTools = enabledServers.filter { srv -> srv.tools.any { it.enable } }
        if (serversWithTools.isEmpty()) return ""

        return buildString {
            append("## Model Context Protocol (MCP) Server Infrastructure\n")
            append("You have access to external Model Context Protocol (MCP) servers (https://modelcontextprotocol.io/).\n")
            append("When executing tasks, select and invoke the appropriate tool associated with the matching MCP server:\n\n")
            for (srv in serversWithTools) {
                val activeTools = srv.tools.filter { it.enable }
                append("### MCP Server: [${srv.name}]\n")
                if (srv.url.isNotBlank()) append("- Endpoint: `${srv.url}`\n")
                append("- Active Tools (${activeTools.size}):\n")
                for (tool in activeTools) {
                    val desc = if (!tool.description.isNullOrBlank()) tool.description else "Action provided by MCP server '${srv.name}'"
                    append("  * `${tool.name}`: $desc\n")
                }
                append("\n")
            }
        }
    }
}

/**
 * Parses Claude desktop / standard MCP JSON configuration into a list of McpServerConfig.
 */
fun parseMcpServersFromJson(jsonText: String): List<McpServerConfig> {
    val results = mutableListOf<McpServerConfig>()
    val trimmed = jsonText.trim()
    try {
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            if (obj.has("mcpServers")) {
                val mcpServersObj = obj.getJSONObject("mcpServers")
                val keys = mcpServersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val srvObj = mcpServersObj.getJSONObject(key)
                    val url = srvObj.optString("url", "").trim()
                    if (url.isNotBlank()) {
                        val headersList = mutableListOf<Pair<String, String>>()
                        val headersObj = srvObj.optJSONObject("headers")
                        headersObj?.keys()?.forEach { hKey ->
                            headersList.add(hKey to headersObj.optString(hKey))
                        }
                        val transportType = srvObj.optString("type", "streamable_http")
                        val config = if (transportType == "sse") {
                            McpServerConfig.SseTransportServer(
                                commonOptions = McpCommonOptions(
                                    enable = true,
                                    name = key,
                                    headers = headersList,
                                    tools = emptyList()
                                ),
                                url = url
                            )
                        } else {
                            McpServerConfig.StreamableHTTPServer(
                                commonOptions = McpCommonOptions(
                                    enable = true,
                                    name = key,
                                    headers = headersList,
                                    tools = emptyList()
                                ),
                                url = url
                            )
                        }
                        results.add(config)
                    }
                }
            } else {
                val url = obj.optString("url", "").trim()
                if (url.isNotBlank()) {
                    val name = obj.optString("name", "MCP Server")
                    val headersList = mutableListOf<Pair<String, String>>()
                    val headersObj = obj.optJSONObject("headers")
                    headersObj?.keys()?.forEach { hKey ->
                        headersList.add(hKey to headersObj.optString(hKey))
                    }
                    results.add(
                        McpServerConfig.StreamableHTTPServer(
                            commonOptions = McpCommonOptions(
                                enable = true,
                                name = name,
                                headers = headersList,
                                tools = emptyList()
                            ),
                            url = url
                        )
                    )
                }
            }
        } else if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val url = item.optString("url", "").trim()
                if (url.isNotBlank()) {
                    val name = item.optString("name", "Server $i")
                    val headersList = mutableListOf<Pair<String, String>>()
                    val headersObj = item.optJSONObject("headers")
                    headersObj?.keys()?.forEach { hKey ->
                        headersList.add(hKey to headersObj.optString(hKey))
                    }
                    results.add(
                        McpServerConfig.StreamableHTTPServer(
                            commonOptions = McpCommonOptions(
                                enable = true,
                                name = name,
                                headers = headersList,
                                tools = emptyList()
                            ),
                            url = url
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e("McpManager", "Failed to parse MCP JSON: ${e.message}")
    }
    return results
}
