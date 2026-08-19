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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Dynamic MCP Server Manager with Disk Persistence.
 * Tools are dynamically introspected from the MCP server and saved to disk
 * so added servers and configured tools never disappear across app restarts.
 */
@Singleton
class McpManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("bit_mcp_servers_store", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow<List<McpServerConfig>>(loadSavedServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    init {
        // Automatically introspect and sync tools from all enabled MCP servers on startup
        scope.launch {
            syncAll()
        }
    }

    companion object {
        private const val TAG = "McpManager"
    }

    private fun loadSavedServers(): List<McpServerConfig> {
        val rawJson = prefs.getString("servers_json", null) ?: return emptyList()

        return try {
            val arr = JSONArray(rawJson)
            val list = mutableListOf<McpServerConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
                val toolsList = mutableListOf<McpToolConfig>()
                for (j in 0 until toolsArr.length()) {
                    val t = toolsArr.getJSONObject(j)
                    toolsList.add(
                        McpToolConfig(
                            name = t.optString("name"),
                            description = t.optString("description"),
                            isEnabled = t.optBoolean("isEnabled", true),
                            inputSchemaJson = t.optString("inputSchemaJson", "{}")
                        )
                    )
                }
                list.add(
                    McpServerConfig(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name"),
                        url = obj.optString("url"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        tools = toolsList,
                        status = McpStatus.Idle
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved MCP servers", e)
            emptyList()
        }
    }

    private fun persistServers(serversList: List<McpServerConfig>) {
        try {
            val arr = JSONArray()
            for (srv in serversList) {
                val srvObj = JSONObject().apply {
                    put("id", srv.id)
                    put("name", srv.name)
                    put("url", srv.url)
                    put("isEnabled", srv.isEnabled)
                    val toolsArr = JSONArray()
                    for (t in srv.tools) {
                        toolsArr.put(JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            put("isEnabled", t.isEnabled)
                            put("inputSchemaJson", t.inputSchemaJson)
                        })
                    }
                    put("tools", toolsArr)
                }
                arr.put(srvObj)
            }
            prefs.edit().putString("servers_json", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting MCP servers", e)
        }
    }

    /**
     * Connects to the given server, performs JSON-RPC handshake, dynamically queries `tools/list`,
     * and updates the server's tool registry.
     */
    suspend fun syncServer(serverId: String): Result<List<McpToolConfig>> {
        val server = _servers.value.find { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Server not found: $serverId"))

        updateServerStatus(serverId, McpStatus.Connecting)

        return try {
            val client = McpClient(server)
            val fetchedTools = client.listTools()
            updateServerTools(serverId, fetchedTools, McpStatus.Connected)
            Log.i(TAG, "Successfully synced ${fetchedTools.size} dynamic tools from server: ${server.name}")
            Result.success(fetchedTools)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync MCP server ${server.name}", e)
            val errorMsg = e.message ?: "Connection failed"
            updateServerStatus(serverId, McpStatus.Error(errorMsg))
            Result.failure(e)
        }
    }

    /**
     * Syncs tools from all enabled servers concurrently.
     */
    suspend fun syncAll() {
        _servers.value.filter { it.isEnabled }.forEach { server ->
            try {
                syncServer(server.id)
            } catch (e: Exception) {
                Log.w(TAG, "Initial sync skipped for ${server.name}: ${e.message}")
            }
        }
    }

    fun addServer(name: String, url: String, headers: Map<String, String> = emptyMap()) {
        val newConfig = McpServerConfig(
            name = name.trim(),
            url = url.trim(),
            isEnabled = true,
            headers = headers,
            tools = emptyList(),
            status = McpStatus.Idle
        )
        val updated = _servers.value + newConfig
        _servers.value = updated
        persistServers(updated)
        scope.launch {
            syncServer(newConfig.id)
        }
    }

    fun addServers(newServers: List<McpServerConfig>) {
        if (newServers.isEmpty()) return
        val updated = _servers.value + newServers
        _servers.value = updated
        persistServers(updated)
        scope.launch {
            newServers.forEach { srv ->
                syncServer(srv.id)
            }
        }
    }

    fun reorderServers(fromIndex: Int, toIndex: Int) {
        val list = _servers.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _servers.value = list
            persistServers(list)
            com.bit.plugins.PluginManager.invalidateToolCache()
        }
    }

    fun setOrderedServers(orderedList: List<McpServerConfig>) {
        _servers.value = orderedList
        persistServers(orderedList)
        com.bit.plugins.PluginManager.invalidateToolCache()
    }

    fun removeServer(serverId: String) {
        val updated = _servers.value.filter { it.id != serverId }
        _servers.value = updated
        persistServers(updated)
        com.bit.plugins.PluginManager.invalidateToolCache()
    }

    fun toggleServer(serverId: String, isEnabled: Boolean) {
        val updated = _servers.value.map {
            if (it.id == serverId) it.copy(isEnabled = isEnabled) else it
        }
        _servers.value = updated
        persistServers(updated)
        if (isEnabled) {
            scope.launch { syncServer(serverId) }
        } else {
            com.bit.plugins.PluginManager.invalidateToolCache()
        }
    }

    fun toggleTool(serverId: String, toolName: String, isEnabled: Boolean) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                val updatedTools = server.tools.map { tool ->
                    if (tool.name == toolName) tool.copy(isEnabled = isEnabled) else tool
                }
                server.copy(tools = updatedTools)
            } else {
                server
            }
        }
        _servers.value = updated
        persistServers(updated)
        com.bit.plugins.PluginManager.invalidateToolCache()
    }

    suspend fun executeTool(serverId: String, toolName: String, args: JSONObject): String {
        val server = _servers.value.find { it.id == serverId }
            ?: throw IllegalArgumentException("Server not found: $serverId")
        val client = McpClient(server)
        return client.callTool(toolName, args)
    }

    private fun updateServerStatus(serverId: String, status: McpStatus) {
        _servers.value = _servers.value.map {
            if (it.id == serverId) it.copy(status = status) else it
        }
    }

    private fun updateServerTools(serverId: String, newTools: List<McpToolConfig>, status: McpStatus) {
        val updated = _servers.value.map { server ->
            if (server.id == serverId) {
                val existingTools = server.tools.toMutableList()
                val merged = mutableListOf<McpToolConfig>()

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

                server.copy(tools = merged, status = status)
            } else {
                server
            }
        }
        _servers.value = updated
        persistServers(updated)
        com.bit.plugins.PluginManager.invalidateToolCache()
    }

    /**
     * Generates a structured markdown catalog of all active MCP servers and their capabilities
     * following the Model Context Protocol specification (https://modelcontextprotocol.io/).
     */
    fun getMcpCatalogPrompt(): String {
        val enabledServers = _servers.value.filter { it.isEnabled }
        val serversWithTools = enabledServers.filter { srv -> srv.tools.any { it.isEnabled } }
        if (serversWithTools.isEmpty()) return ""

        return buildString {
            append("## Model Context Protocol (MCP) Server Infrastructure\n")
            append("You have direct access to external Model Context Protocol (MCP) servers (adhering to https://modelcontextprotocol.io/).\n")
            append("Each MCP server encapsulates an external tool ecosystem or service integration.\n")
            append("When executing tasks, select and invoke the appropriate tool associated with the matching MCP server:\n\n")
            for (srv in serversWithTools) {
                val activeTools = srv.tools.filter { it.isEnabled }
                append("### MCP Server: [${srv.name}]\n")
                if (srv.url.isNotBlank()) append("- Endpoint: `${srv.url}`\n")
                append("- Active Tools (${activeTools.size}):\n")
                for (tool in activeTools) {
                    val desc = if (tool.description.isNotBlank()) tool.description else "Action provided by MCP server '${srv.name}'"
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
                        val headersMap = mutableMapOf<String, String>()
                        val headersObj = srvObj.optJSONObject("headers")
                        headersObj?.keys()?.forEach { hKey ->
                            headersMap[hKey] = headersObj.optString(hKey)
                        }
                        results.add(
                            McpServerConfig(
                                name = key,
                                url = url,
                                headers = headersMap,
                                isEnabled = true,
                                tools = emptyList(),
                                status = McpStatus.Idle
                            )
                        )
                    }
                }
            } else {
                val url = obj.optString("url", "").trim()
                if (url.isNotBlank()) {
                    val name = obj.optString("name", "MCP Server")
                    val headersMap = mutableMapOf<String, String>()
                    val headersObj = obj.optJSONObject("headers")
                    headersObj?.keys()?.forEach { hKey ->
                        headersMap[hKey] = headersObj.optString(hKey)
                    }
                    results.add(
                        McpServerConfig(
                            name = name,
                            url = url,
                            headers = headersMap,
                            isEnabled = true,
                            tools = emptyList(),
                            status = McpStatus.Idle
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
                    val headersMap = mutableMapOf<String, String>()
                    val headersObj = item.optJSONObject("headers")
                    headersObj?.keys()?.forEach { hKey ->
                        headersMap[hKey] = headersObj.optString(hKey)
                    }
                    results.add(
                        McpServerConfig(
                            name = name,
                            url = url,
                            headers = headersMap,
                            isEnabled = true,
                            tools = emptyList(),
                            status = McpStatus.Idle
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
