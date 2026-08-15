package com.bit.network.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.bit.data.VaultManager
import com.bit.di.AppContainer
import com.bit.models.messages.ContentType
import com.bit.models.messages.MessageContent
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.models.table_schema.Model
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Pure Native Android ServerSocket HTTP/SSE Server.
 * Serves the compiled React Router SPA from Android assets with full REST/SSE APIs.
 */
class BitWebAccessServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _activePort = MutableStateFlow(8080)
    val activePort: StateFlow<Int> = _activePort.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    companion object {
        private const val TAG = "BitWebAccessServer"
        private const val DEFAULT_PORT = 8080
    }

    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (_isRunning.value) return true

        return try {
            val ipAddress = getLocalIpAddress()
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port), 100)
            serverSocket = socket

            _activePort.value = port
            _serverUrl.value = "http://$ipAddress:$port"
            _isRunning.value = true

            serverJob = scope.launch(Dispatchers.IO) {
                Log.i(TAG, "BIT Web Access Server running on port $port")
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 15000
                        launch(Dispatchers.IO) {
                            handleClientSocket(client)
                        }
                    } catch (e: Exception) {
                        if (socket.isClosed) break
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind server socket on port $port", e)
            _isRunning.value = false
            false
        }
    }

    fun stop() {
        try {
            _isRunning.value = false
            _serverUrl.value = ""
            serverJob?.cancel()
            serverJob = null
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "BIT Web Access Server stopped immediately")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") || hostAddress.startsWith("172."))) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine local IPv4", e)
        }
        return "127.0.0.1"
    }

    private suspend fun buildFullSettingsDto(): JSONObject {
        val models = try {
            AppContainer.getModelRepository().getAllModels().first()
        } catch (e: Exception) {
            emptyList<Model>()
        }
        val activeModel = models.firstOrNull { it.isActive }
        val assistantId = "default"

        val assistantObj = JSONObject().apply {
            put("id", assistantId)
            put("name", "BIT Assistant")
            put("description", "On-Device Neural AI")
            put("model", activeModel?.modelName ?: "On-Device Local Model")
            put("avatar", "")
            put("prompt", "You are BIT AI, an intelligent on-device assistant.")
            put("temperature", 0.7)
            put("topP", 0.9)
            put("contextSize", 4096)
            put("maxTokens", 2048)
            put("systemPrompt", "")
            put("presetMessages", JSONArray())
            put("skillIds", JSONArray())
        }

        val displaySetting = JSONObject().apply {
            put("theme", "dark")
            put("fontSize", 14)
            put("sendOnEnter", true)
            put("codeHighlighting", true)
        }

        return JSONObject().apply {
            put("status", "ONLINE")
            put("app", "BIT AI")
            put("version", "1.0.0")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.RELEASE)
            put("dynamicColor", true)
            put("themeId", "dark")
            put("developerMode", false)
            put("displaySetting", displaySetting)
            put("enableWebSearch", true)
            put("favoriteModels", JSONArray())
            put("chatModelId", activeModel?.modelName ?: "default")
            put("assistantId", assistantId)
            put("currentAssistantId", assistantId)
            put("activeModel", activeModel?.modelName ?: "None Loaded")
            put("modelType", if (activeModel != null) "Local GGUF" else "Standby")
            put("providers", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "local")
                    put("name", "On-Device Neural Engine")
                    put("type", "local")
                    put("models", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", activeModel?.modelName ?: "default")
                            put("name", activeModel?.modelName ?: "On-Device Local Model")
                            put("type", "chat")
                        })
                    })
                })
            })
            put("assistants", JSONArray().apply { put(assistantObj) })
            put("assistantTags", JSONArray())
            put("modeInjections", JSONArray())
            put("lorebooks", JSONArray())
            put("mcpServers", JSONArray())
            put("searchServices", JSONArray())
            put("searchServiceSelected", 0)
            put("webServerJwtEnabled", false)
            put("webServerAuthRequired", false)
            put("generationJobs", JSONObject())
        }
    }

    // ── HTTP / SSE Request Handler ──

    private suspend fun handleClientSocket(socket: Socket) {
        var keepOpenForSse = false
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]
            val path = uri.substringBefore("?").trimEnd('/')
            val query = if (uri.contains("?")) uri.substringAfter("?") else ""

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val headerLine = input.readLine() ?: break
                if (headerLine.isBlank()) break
                val colonIdx = headerLine.indexOf(":")
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Always read body if present
            var bodyStr = ""
            if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = input.read(bodyChars, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                bodyStr = String(bodyChars, 0, readTotal)
            }
            val bodyJson = try { if (bodyStr.isNotBlank()) JSONObject(bodyStr) else JSONObject() } catch (e: Exception) { JSONObject() }

            // Handle CORS OPTIONS preflight
            if (method == "OPTIONS") {
                val res = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With\r\n\r\n"
                output.write(res.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                return
            }

            when {
                // ── API ROUTES FOR LASTCHAT REACT SPA ──

                // GET /api/settings/stream -> Settings SSE stream (Required by root.tsx useSettingsSubscription)
                method == "GET" && path == "/api/settings/stream" -> {
                    keepOpenForSse = true
                    val sseHeader = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=UTF-8\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(sseHeader.toByteArray(StandardCharsets.UTF_8))
                    output.flush()

                    // Send initial update event with full settings
                    val settingsDto = buildFullSettingsDto()
                    sendSsePayload(output, "update", settingsDto.toString())

                    // Keep alive loop
                    while (coroutineContext.isActive && !socket.isClosed) {
                        try {
                            output.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                            output.flush()
                            delay(15000)
                        } catch (e: Exception) {
                            break
                        }
                    }
                }

                // GET /api/status or /api/settings or /api/bootstrap
                method == "GET" && (path == "/api/status" || path == "/api/settings" || path == "/api/bootstrap") -> {
                    sendJsonResponse(output, 200, buildFullSettingsDto())
                }

                // GET /api/conversations/stream -> SSE stream for list updates
                method == "GET" && path == "/api/conversations/stream" -> {
                    keepOpenForSse = true
                    val sseHeader = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=UTF-8\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(sseHeader.toByteArray(StandardCharsets.UTF_8))
                    output.flush()

                    // Send initial comment and keep alive loop
                    while (coroutineContext.isActive && !socket.isClosed) {
                        try {
                            output.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                            output.flush()
                            delay(15000)
                        } catch (e: Exception) {
                            break
                        }
                    }
                }

                // GET /api/conversations/paged or /api/conversations or /api/chats
                method == "GET" && (path == "/api/conversations/paged" || path == "/api/conversations" || path == "/api/chats") -> {
                    val itemsArray = JSONArray()
                    try {
                        val repo = VaultManager.chatRepo
                        if (repo != null) {
                            val allChats = withTimeoutOrNull(2000) { repo.getAllChats() } ?: emptyList()
                            for (chat in allChats) {
                                itemsArray.put(JSONObject().apply {
                                    put("id", chat.chatId)
                                    put("title", if (!chat.title.isNullOrBlank()) chat.title else "Conversation")
                                    put("isPinned", false)
                                    put("createAt", chat.createdAt)
                                    put("updateAt", chat.lastMessageTime ?: chat.createdAt)
                                    put("isGenerating", false)
                                    put("isConsolidated", false)
                                    put("contextSummary", JSONObject.NULL)
                                    put("contextSummaryUpToIndex", 0)
                                    put("lastPruneTime", 0L)
                                    put("lastPruneMessageCount", 0)
                                    put("lastRefreshTime", chat.lastMessageTime ?: chat.createdAt)
                                })
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading conversations", e)
                    }

                    if (path == "/api/conversations/paged") {
                        val response = JSONObject().apply {
                            put("items", itemsArray)
                            put("nextOffset", JSONObject.NULL)
                            put("hasMore", false)
                        }
                        sendJsonResponse(output, 200, response)
                    } else {
                        sendJsonResponse(output, 200, itemsArray)
                    }
                }

                // POST /api/conversations -> Create new conversation
                method == "POST" && path == "/api/conversations" -> {
                    val newId = UUID.randomUUID().toString()
                    try {
                        VaultManager.chatRepo?.createChat(newId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating chat $newId", e)
                    }
                    val json = JSONObject().apply {
                        put("id", newId)
                        put("assistantId", "default")
                    }
                    sendJsonResponse(output, 201, json)
                }

                // GET /api/conversations/:id or /api/messages
                method == "GET" && (path.startsWith("/api/conversations/") || path == "/api/messages") -> {
                    val chatId = if (path.startsWith("/api/conversations/")) {
                        path.removePrefix("/api/conversations/").substringBefore("/")
                    } else {
                        parseQueryParams(query)["chatId"]
                    }

                    if (chatId.isNullOrBlank()) {
                        sendJsonResponse(output, 400, JSONObject().apply { put("error", "Missing chatId") })
                    } else {
                        val nodesArray = JSONArray()
                        try {
                            val repo = VaultManager.chatRepo
                            if (repo != null) {
                                val messages = withTimeoutOrNull(2000) { repo.getMessagesForChat(chatId) } ?: emptyList()
                                var parentId: String? = null
                                for (msg in messages) {
                                    val nodeId = "node-${msg.msgId}"
                                    val roleStr = if (msg.role == Role.User) "user" else "assistant"
                                    val nodeObj = JSONObject().apply {
                                        put("id", nodeId)
                                        put("parentId", if (parentId != null) parentId else JSONObject.NULL)
                                        put("selected", true)
                                        put("message", JSONObject().apply {
                                            put("id", msg.msgId)
                                            put("role", roleStr)
                                            put("createdAt", msg.timestamp)
                                            put("parts", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", msg.content.content)
                                                })
                                            })
                                        })
                                    }
                                    nodesArray.put(nodeObj)
                                    parentId = nodeId
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading messages for $chatId", e)
                        }

                        val convObj = JSONObject().apply {
                            put("id", chatId)
                            put("title", "Conversation")
                            put("assistantId", "default")
                            put("isPinned", false)
                            put("createAt", System.currentTimeMillis())
                            put("updateAt", System.currentTimeMillis())
                            put("isGenerating", false)
                            put("isConsolidated", false)
                            put("messages", nodesArray)
                        }
                        sendJsonResponse(output, 200, convObj)
                    }
                }

                // POST /api/conversations/:id/messages -> Send message & trigger reply
                method == "POST" && (path.contains("/messages") || path == "/api/send") -> {
                    val chatId = if (path.startsWith("/api/conversations/")) {
                        path.removePrefix("/api/conversations/").substringBefore("/")
                    } else {
                        bodyJson.optString("chatId", UUID.randomUUID().toString())
                    }

                    var userText = bodyJson.optString("prompt", bodyJson.optString("message", "")).trim()
                    if (userText.isEmpty()) {
                        val partsArr = bodyJson.optJSONArray("parts")
                        if (partsArr != null && partsArr.length() > 0) {
                            for (i in 0 until partsArr.length()) {
                                val p = partsArr.optJSONObject(i)
                                if (p?.optString("type") == "text") {
                                    userText += p.optString("text", "")
                                }
                            }
                        }
                    }

                    if (userText.isNotEmpty()) {
                        try {
                            val repo = VaultManager.chatRepo
                            if (repo != null) {
                                val userMsg = Messages(
                                    msgId = UUID.randomUUID().toString(),
                                    role = Role.User,
                                    content = MessageContent(contentType = ContentType.Text, content = userText),
                                    timestamp = System.currentTimeMillis()
                                )
                                repo.addMessage(chatId, userMsg)

                                // Generate assistant response
                                val models = try {
                                    AppContainer.getModelRepository().getAllModels().first()
                                } catch (e: Exception) {
                                    emptyList<Model>()
                                }
                                val activeModel = models.firstOrNull { it.isActive }
                                val replyText = if (activeModel != null) {
                                    "Connected to on-device model **${activeModel.modelName}**.\n\nReceived your message: \"$userText\"."
                                } else {
                                    "Message received on your phone: \"$userText\". (No GGUF or Cloud API model is currently active in RAM)."
                                }

                                val assistantMsg = Messages(
                                    msgId = UUID.randomUUID().toString(),
                                    role = Role.Assistant,
                                    content = MessageContent(contentType = ContentType.Text, content = replyText),
                                    timestamp = System.currentTimeMillis()
                                )
                                repo.addMessage(chatId, assistantMsg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error storing message", e)
                        }
                    }

                    sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                }

                // DELETE /api/conversations/:id
                method == "DELETE" && path.startsWith("/api/conversations/") -> {
                    val chatId = path.removePrefix("/api/conversations/").substringBefore("/")
                    try {
                        VaultManager.chatRepo?.deleteChat(chatId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting chat $chatId", e)
                    }
                    sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                }

                // Other POST /api settings routes (pin, regenerate, assistant)
                method == "POST" && path.startsWith("/api/") -> {
                    sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                }

                // ── STATIC ASSETS (React Router 7 SPA) ──
                method == "GET" -> {
                    val rawPath = uri.substringBefore("?")
                    val assetPath = if (rawPath == "/" || rawPath == "/index.html" || !rawPath.contains(".")) {
                        "index.html"
                    } else {
                        rawPath.removePrefix("/")
                    }
                    serveAssetFile(output, assetPath)
                }

                else -> {
                    sendJsonResponse(output, 404, JSONObject().apply { put("error", "Not found") })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handling exception", e)
        } finally {
            if (!keepOpenForSse) {
                try { socket.close() } catch (ignored: Exception) {}
            }
        }
    }

    private fun serveAssetFile(output: OutputStream, assetPath: String) {
        try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val mimeType = guessMimeType(assetPath)
            val cacheControl = if (assetPath.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache"

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mimeType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: $cacheControl\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"

            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            // Fallback for SPA routing to index.html
            if (assetPath != "index.html") {
                serveAssetFile(output, "index.html")
            } else {
                sendJsonResponse(output, 404, JSONObject().apply { put("error", "Asset not found") })
            }
        }
    }

    private fun guessMimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html; charset=UTF-8"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript; charset=UTF-8"
            path.endsWith(".css") -> "text/css; charset=UTF-8"
            path.endsWith(".json") -> "application/json; charset=UTF-8"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=")
            if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            else URLDecoder.decode(parts[0], "UTF-8") to ""
        }
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, json: JSONObject) {
        val statusMsg = when (statusCode) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, jsonArray: JSONArray) {
        val statusMsg = if (statusCode == 200) "OK" else "Internal Server Error"
        val bytes = jsonArray.toString().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendSsePayload(output: OutputStream, event: String, data: String) {
        val payload = "event: $event\ndata: $data\n\n"
        output.write(payload.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }
}
