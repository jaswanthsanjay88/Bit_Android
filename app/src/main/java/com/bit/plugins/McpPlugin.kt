package com.bit.plugins

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.bit.mcp.McpManager
import com.bit.mcp.McpTool
import com.bit.mcp.isEnabled
import com.bit.mcp.name
import com.bit.mcp.tools
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SuperPlugin implementation that connects Model Context Protocol (MCP) servers
 * directly to the on-device and remote AI tool calling pipeline.
 */
class McpPlugin(
    private val context: Context,
    private val mcpManager: McpManager
) : SuperPlugin {

    companion object {
        private const val TAG = "McpPlugin"
        const val PLUGIN_NAME = "MCP Servers"
    }

    override fun getPluginInfo(): PluginInfo {
        val toolDefs = mutableListOf<ToolDefinitionBuilder>()

        val servers = mcpManager.servers.value.filter { it.isEnabled }
        for (server in servers) {
            for (tool in server.tools.filter { it.isEnabled }) {
                val builder = ToolDefinitionBuilder(
                    tool.name,
                    if (!tool.description.isNullOrBlank()) "[MCP: ${server.name}] ${tool.description}"
                    else "[MCP: ${server.name}] External MCP Tool"
                )

                // Parse inputSchema parameters if present
                try {
                    val schemaObj = JSONObject(tool.inputSchemaJson)
                    val props = schemaObj.optJSONObject("properties")
                    val requiredArr = schemaObj.optJSONArray("required")
                    val requiredSet = mutableSetOf<String>()
                    if (requiredArr != null) {
                        for (i in 0 until requiredArr.length()) requiredSet.add(requiredArr.getString(i))
                    }

                    if (props != null) {
                        val keys = props.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val prop = props.optJSONObject(key)
                            val type = prop?.optString("type", "string") ?: "string"
                            val desc = prop?.optString("description", "") ?: ""
                            val isRequired = requiredSet.contains(key)

                            when (type.lowercase()) {
                                "number", "integer" -> builder.numberParam(key, desc, isRequired)
                                "boolean" -> builder.booleanParam(key, desc, isRequired)
                                else -> builder.stringParam(key, desc, isRequired)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing schema for tool ${tool.name}", e)
                }

                toolDefs.add(builder)
            }
        }

        return PluginInfo(
            name = PLUGIN_NAME,
            description = "External Model Context Protocol (MCP) server tools",
            author = "Model Context Protocol",
            version = "1.0.0",
            toolDefinitionBuilder = toolDefs
        )
    }

    override fun serializeResult(data: Any): String {
        return when (data) {
            is JSONObject -> data.toString()
            is String -> data
            else -> data.toString()
        }
    }

    override suspend fun executeTool(toolCall: ToolCall): Result<Any> = withContext(Dispatchers.IO) {
        val toolName = toolCall.name
        Log.i(TAG, "Executing MCP tool: $toolName with arguments: ${toolCall.arguments}")

        try {
            // Locate server and exact tool definition that matches (supporting direct, namespaced, and hyphen/underscore variations)
            val servers = mcpManager.servers.value.filter { it.isEnabled }
            var targetServerId: String? = null
            var canonicalToolName = toolName

            for (srv in servers) {
                val srvClean = srv.name.trim().lowercase().replace(" ", "_").replace("-", "_")
                val matchedTool = srv.tools.firstOrNull { tool ->
                    val toolClean = tool.name.trim().lowercase().replace("-", "_")
                    val callClean = toolName.trim().lowercase().replace("-", "_")

                    tool.isEnabled && (
                        tool.name.equals(toolName, ignoreCase = true) ||
                        toolClean == callClean ||
                        callClean == "${srvClean}__${toolClean}" ||
                        callClean == "${srvClean}_${toolClean}" ||
                        callClean == "mcp__${srvClean}__${toolClean}" ||
                        callClean.endsWith("__$toolClean") ||
                        callClean.endsWith("_$toolClean")
                    )
                }
                if (matchedTool != null) {
                    targetServerId = srv.id
                    canonicalToolName = matchedTool.name
                    break
                }
            }

            if (targetServerId == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("No enabled MCP server found containing tool '$toolName'")
                )
            }

            val result = mcpManager.executeTool(targetServerId, canonicalToolName, toolCall.arguments)
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing MCP tool $toolName", e)
            Result.failure(e)
        }
    }

    @Composable
    override fun ToolCallUI() {
        // Rendered in chat UI
    }

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        // Rendered in chat cache UI
    }
}
