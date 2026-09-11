package com.bit.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Robust MCP (Model Context Protocol) Client.
 * Supports:
 * - Direct HTTP JSON-RPC 2.0 (Streamable HTTP at `/mcp` and custom endpoints)
 * - Automatic `/mcp` and `/sse` route discovery on 404 "use /mcp" fallback
 * - SSE Handshake (`event: endpoint`) POST endpoint resolution
 * - `mcp-session-id` session header propagation
 * - OAuth 2.1 Bearer Token injection & custom headers
 */
class McpClient(
    val serverConfig: McpServerConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "McpClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
    }

    private var activeSessionId: String? = null
    private var resolvedPostUrl: String? = null

    /**
     * Resolves candidate URLs to try for this MCP server.
     */
    private fun getCandidateUrls(base: String): List<String> {
        val trimmed = base.trim().trimEnd('/')
        val candidates = mutableListOf<String>()

        // 1. Always prioritize the exact user-specified URL first
        candidates.add(trimmed)

        if (trimmed.endsWith("/sse")) {
            val root = trimmed.removeSuffix("/sse").trimEnd('/')
            candidates.add("$root/mcp")
            candidates.add(root)
        } else if (trimmed.endsWith("/mcp")) {
            val root = trimmed.removeSuffix("/mcp").trimEnd('/')
            candidates.add("$root/sse")
            candidates.add(root)
        } else {
            candidates.add("$trimmed/mcp")
            candidates.add("$trimmed/sse")
            candidates.add("$trimmed/v1/mcp")
        }

        return candidates.distinct()
    }

    /**
     * Attempts to perform SSE handshake to discover post endpoint if server is SSE-based.
     */
    private suspend fun trySseEndpointDiscovery(targetUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .get()
                .header("Accept", "text/event-stream")
                .header("User-Agent", "BIT-AI-MCP/2.0")

            applyHeaders(reqBuilder)

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type") ?: ""
                if (!contentType.contains("text/event-stream")) return@withContext null

                val stream = response.body.byteStream()
                val reader = BufferedReader(InputStreamReader(stream))

                var currentEvent: String? = null
                var lineCount = 0

                while (lineCount < 30) {
                    val line = reader.readLine() ?: break
                    lineCount++

                    if (line.startsWith("event:")) {
                        currentEvent = line.substringAfter("event:").trim()
                    } else if (line.startsWith("data:")) {
                        val data = line.substringAfter("data:").trim()
                        if (currentEvent == "endpoint") {
                            val resolved = if (data.startsWith("http://") || data.startsWith("https://")) {
                                data
                            } else {
                                val rootUrl = targetUrl.substringBefore("://") + "://" +
                                        targetUrl.substringAfter("://").substringBefore("/")
                                rootUrl + if (data.startsWith("/")) data else "/$data"
                            }
                            Log.i(TAG, "Discovered SSE POST endpoint: $resolved")
                            return@withContext resolved
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSE discovery on $targetUrl skipped: ${e.message}")
        }
        null
    }

    private fun applyHeaders(builder: Request.Builder) {
        // 1. Injected custom headers
        serverConfig.headers.forEach { (k, v) ->
            if (k.equals("authorization", ignoreCase = true)) {
                val authVal = if (v.startsWith("Bearer ", ignoreCase = true)) {
                    v
                } else if (v.startsWith("token ", ignoreCase = true)) {
                    "Bearer " + v.substring(6).trim()
                } else {
                    "Bearer $v"
                }
                builder.header("Authorization", authVal)
            } else {
                builder.header(k, v)
            }
        }

        // 2. OAuth Token overrides if present and authorized
        val oauth = serverConfig.commonOptions.oauth
        if (oauth != null && oauth.enabled && !oauth.accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${oauth.accessToken}")
        }
    }

    /**
     * Executes a JSON-RPC request against candidate URLs, handling 404 and auto-discovering endpoints.
     */
    private suspend fun executeJsonRpc(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val candidates = if (!resolvedPostUrl.isNullOrBlank()) {
            listOf(resolvedPostUrl!!)
        } else {
            getCandidateUrls(serverConfig.url)
        }

        var lastError: Exception? = null

        for (candidateUrl in candidates) {
            if (resolvedPostUrl == null && (candidateUrl.endsWith("/sse") || candidateUrl.contains("sse") || serverConfig is McpServerConfig.SseTransportServer)) {
                val ssePostEndpoint = trySseEndpointDiscovery(candidateUrl)
                if (!ssePostEndpoint.isNullOrBlank()) {
                    resolvedPostUrl = ssePostEndpoint
                }
            }

            val requestUrl = resolvedPostUrl ?: candidateUrl

            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "BIT-AI-MCP/2.0")
                .apply {
                    if (!activeSessionId.isNullOrBlank()) {
                        header(MCP_SESSION_ID_HEADER, activeSessionId!!)
                    }
                    applyHeaders(this)
                }

            try {
                val response: Response = client.newCall(requestBuilder.build()).execute()
                response.header(MCP_SESSION_ID_HEADER)?.let {
                    activeSessionId = it
                }

                val body = response.body.string()

                if (response.isSuccessful) {
                    resolvedPostUrl = requestUrl
                    return@withContext parseJsonRpcResponse(body)
                }

                if (response.code == 401 || response.code == 403) {
                    throw IOException("HTTP ${response.code} Unauthorized: $body")
                }

                if (response.code == 404) {
                    Log.w(TAG, "Endpoint $requestUrl returned 404, probing next candidate...")
                    lastError = IOException("HTTP ${response.code}: $body")
                    continue
                }

                throw IOException("Server returned HTTP ${response.code}: $body")
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "Attempt on $requestUrl failed: ${e.message}")
            }
        }

        throw lastError ?: IOException("Failed to connect to MCP server ${serverConfig.url}")
    }

    private fun parseJsonRpcResponse(bodyString: String): JSONObject {
        val trimmed = bodyString.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }

        // Handle inline SSE event response (data: {...})
        for (line in trimmed.lines()) {
            if (line.startsWith("data:")) {
                val jsonPart = line.substringAfter("data:").trim()
                if (jsonPart.startsWith("{")) {
                    return JSONObject(jsonPart)
                }
            }
        }

        return JSONObject()
    }

    /**
     * Connects to the MCP server, sends `initialize` request, and queries `tools/list`.
     * Returns the list of discovered tools.
     */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        // 1. Initialize Handshake
        val initPayload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject().apply {
                    put("roots", JSONObject().apply { put("listChanged", true) })
                    put("sampling", JSONObject())
                })
                put("clientInfo", JSONObject().apply {
                    put("name", "BIT AI Android")
                    put("version", "2.1.0")
                })
            })
        }

        try {
            executeJsonRpc(initPayload)
        } catch (e: Exception) {
            Log.w(TAG, "MCP initialize handshake note: ${e.message}")
        }

        // 2. Query tools/list
        val toolsPayload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
            put("params", JSONObject())
        }

        val json = executeJsonRpc(toolsPayload)
        val result = json.optJSONObject("result") ?: json
        val toolsArray = result.optJSONArray("tools") ?: JSONArray()

        val discoveredTools = mutableListOf<McpTool>()
        for (i in 0 until toolsArray.length()) {
            val toolObj = toolsArray.optJSONObject(i) ?: continue
            val name = toolObj.optString("name", "tool_$i")
            val desc = toolObj.optString("description", "")
            val schema = toolObj.optJSONObject("inputSchema")?.toString() ?: "{}"

            discoveredTools.add(
                McpTool(
                    name = name,
                    description = desc,
                    enable = true,
                    inputSchemaJson = schema,
                    needsApproval = false
                )
            )
        }

        discoveredTools
    }

    /**
     * Executes a tool on the remote MCP server.
     */
    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            })
        }

        val json = executeJsonRpc(payload)
        val result = json.optJSONObject("result")
        val contentArr = result?.optJSONArray("content")
        if (contentArr != null && contentArr.length() > 0) {
            val textParts = mutableListOf<String>()
            for (i in 0 until contentArr.length()) {
                val item = contentArr.optJSONObject(i)
                if (item?.optString("type") == "text") {
                    textParts.add(item.optString("text", ""))
                }
            }
            if (textParts.isNotEmpty()) return@withContext textParts.joinToString("\n")
        }

        if (result != null) return@withContext result.toString()
        if (json.has("error")) {
            val errObj = json.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: json.optString("error")
            throw IOException("MCP tool execution error: $errMsg")
        }

        json.toString()
    }
}
