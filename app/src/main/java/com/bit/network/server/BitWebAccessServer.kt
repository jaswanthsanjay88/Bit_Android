package com.bit.network.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.bit.data.AppSettingsDataStore
import com.bit.data.VaultManager
import com.bit.di.AppContainer
import com.bit.engine.GenerationEvent
import com.bit.models.messages.ContentType
import com.bit.models.messages.MessageContent
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.models.table_schema.Model
import com.bit.worker.ActiveModelSession
import com.bit.worker.LlmModelWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Robust Native Android HTTP & Server-Sent Events (SSE) Server.
 * Serves the React Router SPA web client from assets and provides full real-time REST & SSE APIs.
 */
class BitWebAccessServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _activePort = MutableStateFlow(7070)
    val activePort: StateFlow<Int> = _activePort.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    // SSE active client maps
    private val conversationSseClients = ConcurrentHashMap<String, MutableSet<OutputStream>>()
    private val settingsSseClients = ConcurrentHashMap.newKeySet<OutputStream>()
    private val conversationListSseClients = ConcurrentHashMap.newKeySet<OutputStream>()

    // Generation tracking
    private val activeGenerations = ConcurrentHashMap<String, Job>()
    private val sequenceCounter = AtomicInteger(1)

    companion object {
        private const val TAG = "BitWebAccessServer"
        private const val DEFAULT_PORT = 7070
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
                Log.i(TAG, "BIT Web Access Server running on port $port (URL: http://$ipAddress:$port)")
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 30000
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

            // Stop all active generations
            activeGenerations.values.forEach { it.cancel() }
            activeGenerations.clear()

            conversationSseClients.clear()
            settingsSseClients.clear()
            conversationListSseClients.clear()

            Log.i(TAG, "BIT Web Access Server stopped")
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
        val activeGgufId = LlmModelWorker.currentGgufModelId.value
        val activeModel = models.firstOrNull { it.modelName == activeGgufId || it.isActive } ?: models.firstOrNull()
        val assistantId = "default"

        val assistantObj = JSONObject().apply {
            put("id", assistantId)
            put("name", "BIT Assistant")
            put("description", "On-Device Neural AI")
            put("model", activeModel?.modelName ?: "On-Device Local Model")
            put("avatar", JSONObject().apply {
                put("type", "emoji")
                put("content", "🤖")
            })
            put("useAssistantAvatar", false)
            put("prompt", "You are BIT AI, an intelligent on-device assistant.")
            put("temperature", 0.7)
            put("topP", 0.9)
            put("contextSize", 4096)
            put("maxTokens", 2048)
            put("systemPrompt", "")
            put("tags", JSONArray())
            put("quickMessages", JSONArray())
            put("presetMessages", JSONArray())
            put("skillIds", JSONArray())
            put("modeInjectionIds", JSONArray())
            put("lorebookIds", JSONArray())
            put("mcpServers", JSONArray())
            put("enableMemory", false)
            put("chatModelId", activeModel?.modelName ?: "default")
        }

        val displaySetting = JSONObject().apply {
            put("userNickname", "User")
            put("showUserAvatar", true)
            put("showModelIcon", true)
            put("showModelName", true)
            put("showAssistantBubbles", true)
            put("showTokenUsage", true)
            put("showThinkingContent", true)
            put("autoCloseThinking", false)
            put("codeBlockAutoWrap", true)
            put("codeBlockAutoCollapse", false)
            put("showLineNumbers", true)
            put("sendOnEnter", true)
            put("enableAutoScroll", true)
            put("fontSizeRatio", 1.0)
            put("pasteLongTextAsFile", false)
            put("pasteLongTextThreshold", 4000)
            put("theme", "dark")
            put("fontSize", 14)
            put("codeHighlighting", true)
        }

        val modelsArray = JSONArray().apply {
            for (m in models) {
                put(JSONObject().apply {
                    put("id", m.modelName)
                    put("modelId", m.modelName)
                    put("name", m.modelName)
                    put("displayName", m.modelName)
                    put("type", "CHAT")
                    put("providerSlug", "local")
                    put("iconUrl", "")
                    put("customIconUri", "")
                    put("inputModalities", JSONArray().apply { put("TEXT") })
                    put("outputModalities", JSONArray().apply { put("TEXT") })
                    put("abilities", JSONArray().apply {
                        put("REASONING")
                        put("TOOL")
                    })
                })
            }
            if (models.isEmpty()) {
                put(JSONObject().apply {
                    put("id", "default")
                    put("modelId", "default")
                    put("name", "On-Device Model")
                    put("displayName", "On-Device Neural Model")
                    put("type", "CHAT")
                    put("providerSlug", "local")
                    put("iconUrl", "")
                    put("customIconUri", "")
                    put("inputModalities", JSONArray().apply { put("TEXT") })
                    put("outputModalities", JSONArray().apply { put("TEXT") })
                    put("abilities", JSONArray().apply {
                        put("REASONING")
                        put("TOOL")
                    })
                })
            }
        }

        val activeModelId = activeModel?.modelName ?: "default"

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
            put("favoriteModels", JSONArray().apply { put(activeModelId) })
            put("chatModelId", activeModelId)
            put("assistantId", assistantId)
            put("currentAssistantId", assistantId)
            put("activeModel", activeModelId)
            put("modelType", if (LlmModelWorker.isGgufModelLoaded.value) "Local GGUF" else "Standby")
            put("providers", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "local")
                    put("name", "On-Device Neural Engine")
                    put("type", "local")
                    put("enabled", true)
                    put("models", modelsArray)
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

    private suspend fun buildConversationDto(chatId: String): JSONObject {
        val repo = VaultManager.chatRepo
        val chatInfo = repo?.getAllChats()?.firstOrNull { it.chatId == chatId }
        val messages = withTimeoutOrNull(2000) { repo?.getMessagesForChat(chatId) } ?: emptyList()

        val nodesArray = JSONArray()
        var nodeIdx = 0

        for (msg in messages) {
            val nodeId = "node-${msg.msgId}"
            val roleStr = if (msg.role == Role.User) "user" else "assistant"
            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content.content)
                })
            }

            val msgDto = JSONObject().apply {
                put("id", msg.msgId)
                put("role", roleStr)
                put("parts", partsArray)
                put("createdAt", formatIsoTimestamp(msg.timestamp ?: System.currentTimeMillis()))
                put("modelId", LlmModelWorker.currentGgufModelId.value ?: "default")
            }

            val nodeDto = JSONObject().apply {
                put("id", nodeId)
                put("messages", JSONArray().apply { put(msgDto) })
                put("selectIndex", 0)
            }
            nodesArray.put(nodeDto)
            nodeIdx++
        }

        val isGenerating = activeGenerations.containsKey(chatId)

        return JSONObject().apply {
            put("id", chatId)
            put("assistantId", "default")
            put("title", chatInfo?.title?.takeIf { it.isNotBlank() } ?: "Conversation")
            put("messages", nodesArray)
            put("enabledSkillIds", JSONArray())
            put("truncateIndex", -1)
            put("chatSuggestions", JSONArray())
            put("isPinned", false)
            put("createAt", chatInfo?.createdAt ?: System.currentTimeMillis())
            put("updateAt", chatInfo?.lastMessageTime ?: System.currentTimeMillis())
            put("isGenerating", isGenerating)
            put("isFork", false)
            put("isConsolidated", false)
            put("contextSummary", JSONObject.NULL)
            put("contextSummaryUpToIndex", -1)
            put("lastPruneTime", 0L)
            put("lastPruneMessageCount", 0)
            put("lastRefreshTime", 0L)
        }
    }

    private fun formatIsoTimestamp(epochMs: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(epochMs))
    }

    // ── HTTP / SSE Connection Handler ──

    private suspend fun handleClientSocket(socket: Socket) {
        var keepOpenForSse = false
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = BufferedOutputStream(socket.getOutputStream())

            while (scope.isActive && !socket.isClosed) {
                val requestLine = input.readLine() ?: break
                if (requestLine.isBlank()) continue

                val parts = requestLine.split(" ")
                if (parts.size < 2) break

                val method = parts[0].uppercase()
                val uri = parts[1]
                val path = uri.substringBefore("?").trimEnd('/')
                val query = if (uri.contains("?")) uri.substringAfter("?") else ""

                // Read request headers
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

                // Read request body if present
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
                val bodyJson = try { if (bodyStr.isNotBlank()) JSONObject(bodyStr) else JSONObject() } catch (_: Exception) { JSONObject() }

                // Handle CORS preflight
                if (method == "OPTIONS") {
                    val res = "HTTP/1.1 204 No Content\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With, Accept\r\n\r\n"
                    output.write(res.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    continue
                }

                when {
                    // ── SSE: Settings Stream ──
                    method == "GET" && path == "/api/settings/stream" -> {
                        keepOpenForSse = true
                        startSseStream(output)
                        settingsSseClients.add(output)

                        // Send initial update event
                        val settingsDto = buildFullSettingsDto()
                        sendSsePayload(output, "update", settingsDto.toString())

                        try {
                            while (scope.isActive && !socket.isClosed) {
                                output.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                                output.flush()
                                delay(15000)
                            }
                        } finally {
                            settingsSseClients.remove(output)
                        }
                        break
                    }

                    // ── SSE: Conversation List Stream ──
                    method == "GET" && path == "/api/conversations/stream" -> {
                        keepOpenForSse = true
                        startSseStream(output)
                        conversationListSseClients.add(output)

                        try {
                            while (scope.isActive && !socket.isClosed) {
                                output.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                                output.flush()
                                delay(15000)
                            }
                        } finally {
                            conversationListSseClients.remove(output)
                        }
                        break
                    }

                    // ── SSE: Conversation Detail Stream (/api/conversations/:id/stream) ──
                    method == "GET" && path.startsWith("/api/conversations/") && path.endsWith("/stream") -> {
                        keepOpenForSse = true
                        val chatId = path.removePrefix("/api/conversations/").removeSuffix("/stream").substringBefore("/")

                        startSseStream(output)
                        val clientSet = conversationSseClients.computeIfAbsent(chatId) { ConcurrentHashMap.newKeySet() }
                        clientSet.add(output)

                        // Send initial snapshot event
                        val convDto = buildConversationDto(chatId)
                        val snapshot = JSONObject().apply {
                            put("type", "snapshot")
                            put("seq", sequenceCounter.incrementAndGet())
                            put("conversation", convDto)
                            put("serverTime", System.currentTimeMillis())
                        }
                        sendSsePayload(output, "snapshot", snapshot.toString())

                        try {
                            while (scope.isActive && !socket.isClosed) {
                                output.write(": heartbeat\n\n".toByteArray(StandardCharsets.UTF_8))
                                output.flush()
                                delay(15000)
                            }
                        } finally {
                            clientSet.remove(output)
                            if (clientSet.isEmpty()) {
                                conversationSseClients.remove(chatId)
                            }
                        }
                        break
                    }

                    // ── REST: Bootstrap & Status & Settings ──
                    method == "GET" && (path == "/api/status" || path == "/api/settings" || path == "/api/bootstrap") -> {
                        val settings = buildFullSettingsDto()
                        sendJsonResponse(output, 200, settings)
                    }

                    // ── REST: AI Icon Proxy ──
                    method == "GET" && path == "/api/ai-icon" -> {
                        serveAssetFile(output, "ic_logo.svg")
                    }

                    // ── REST: Conversation List (Paged) ──
                    method == "GET" && (path == "/api/conversations/paged" || path == "/api/conversations" || path == "/api/chats") -> {
                        val itemsArray = JSONArray()
                        try {
                            val repo = VaultManager.chatRepo
                            if (repo != null) {
                                val allChats = withTimeoutOrNull(2000) { repo.getAllChats() } ?: emptyList()
                                for (chat in allChats) {
                                    itemsArray.put(JSONObject().apply {
                                        put("id", chat.chatId)
                                        put("assistantId", "default")
                                        put("title", if (!chat.title.isNullOrBlank()) chat.title else "Conversation")
                                        put("isPinned", false)
                                        put("createAt", chat.createdAt)
                                        put("updateAt", chat.lastMessageTime ?: chat.createdAt)
                                        put("isGenerating", activeGenerations.containsKey(chat.chatId))
                                        put("isFork", false)
                                        put("isConsolidated", false)
                                        put("contextSummary", JSONObject.NULL)
                                        put("contextSummaryUpToIndex", -1)
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

                    // ── REST: Create Conversation ──
                    method == "POST" && path == "/api/conversations" -> {
                        val newId = bodyJson.optString("id", UUID.randomUUID().toString())
                        val repo = VaultManager.chatRepo ?: run {
                            VaultManager.initPlaintext(context)
                            VaultManager.chatRepo
                        }
                        try {
                            repo?.createChat(newId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error creating chat $newId", e)
                        }
                        notifyConversationListChanged()
                        val json = JSONObject().apply {
                            put("id", newId)
                            put("assistantId", "default")
                        }
                        sendJsonResponse(output, 201, json)
                    }

                    // ── REST: Get Conversation Details ──
                    method == "GET" && (path.startsWith("/api/conversations/") || path == "/api/messages") -> {
                        val chatId = if (path.startsWith("/api/conversations/")) {
                            path.removePrefix("/api/conversations/").substringBefore("/")
                        } else {
                            parseQueryParams(query)["chatId"]
                        }

                        if (chatId.isNullOrBlank()) {
                            sendJsonResponse(output, 400, JSONObject().apply { put("error", "Missing chatId") })
                        } else {
                            val convObj = buildConversationDto(chatId)
                            sendJsonResponse(output, 200, convObj)
                        }
                    }

                    // ── REST: Send Message & Trigger Real AI Inference ──
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
                            val repo = VaultManager.chatRepo ?: run {
                                VaultManager.initPlaintext(context)
                                VaultManager.chatRepo
                            }
                            val existingMsgs = withTimeoutOrNull(2000) { repo?.getMessagesForChat(chatId) } ?: emptyList()
                            val userNodeIndex = existingMsgs.size
                            val userMsgId = UUID.randomUUID().toString()
                            val userNodeId = "node-$userMsgId"
                            val userMsg = Messages(
                                msgId = userMsgId,
                                role = Role.User,
                                content = MessageContent(contentType = ContentType.Text, content = userText),
                                timestamp = System.currentTimeMillis()
                            )
                            if (existingMsgs.isEmpty()) {
                                repo?.createChat(chatId)
                                val title = if (userText.length > 30) userText.take(30) + "..." else userText
                                repo?.updateChatTitle(chatId, title)
                            }
                            repo?.addMessage(chatId, userMsg)

                            // Emit User message node update to SSE clients immediately!
                            emitNodeUpdate(
                                chatId = chatId,
                                nodeId = userNodeId,
                                nodeIndex = userNodeIndex,
                                messageId = userMsgId,
                                role = "user",
                                text = userText,
                                isGenerating = true
                            )

                            notifyConversationListChanged()

                            // Launch live AI inference at assistant node index
                            startInferenceForWebClient(chatId, userText, userNodeIndex + 1)
                        }

                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Delete Single Message ──
                    method == "DELETE" && path.contains("/messages/") -> {
                        val messageId = path.substringAfter("/messages/").substringBefore("/")
                        val repo = VaultManager.chatRepo
                        repo?.deleteMessage(messageId)
                        notifyConversationListChanged()
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Stop Generation ──
                    method == "POST" && path.endsWith("/stop") -> {
                        val chatId = path.removePrefix("/api/conversations/").removeSuffix("/stop").substringBefore("/")
                        activeGenerations[chatId]?.cancel()
                        activeGenerations.remove(chatId)
                        LlmModelWorker.ggufStopGeneration()
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Regenerate Last Message ──
                    method == "POST" && path.endsWith("/regenerate") -> {
                        val chatId = path.removePrefix("/api/conversations/").removeSuffix("/regenerate").substringBefore("/")
                        val repo = VaultManager.chatRepo
                        val msgs = repo?.getMessagesForChat(chatId) ?: emptyList()
                        val lastUserMsg = msgs.lastOrNull { it.role == Role.User }
                        if (lastUserMsg != null) {
                            startInferenceForWebClient(chatId, lastUserMsg.content.content, msgs.size)
                        }
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Delete Conversation ──
                    method == "DELETE" && path.startsWith("/api/conversations/") -> {
                        val chatId = path.removePrefix("/api/conversations/").substringBefore("/")
                        try {
                            VaultManager.chatRepo?.deleteChat(chatId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting chat $chatId", e)
                        }
                        notifyConversationListChanged()
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Settings Updates (Assistant model, favorites, search) ──
                    method == "POST" && path.startsWith("/api/settings/") -> {
                        val settingsDataStore = AppSettingsDataStore(context)
                        when {
                            path.contains("/assistant/model") -> {
                                val modelId = bodyJson.optString("modelId")
                                if (modelId.isNotBlank()) {
                                    scope.launch {
                                        ActiveModelSession.set(modelId, com.bit.models.enums.ProviderType.GGUF)
                                    }
                                }
                            }
                            path.contains("/search/service") || path.contains("/search/enabled") -> {
                                val provider = bodyJson.optString("provider", "duckduckgo")
                                scope.launch {
                                    settingsDataStore.saveWebSearchProvider(provider)
                                }
                            }
                        }
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── REST: Generic Fallback for other /api POSTs ──
                    method == "POST" && path.startsWith("/api/") -> {
                        sendJsonResponse(output, 200, JSONObject().apply { put("status", "ok") })
                    }

                    // ── STATIC ASSETS (React Router SPA) ──
                    method == "GET" -> {
                        val rawPath = uri.substringBefore("?")
                        val assetPath = if (rawPath.isBlank() || rawPath == "/" || rawPath == "/index.html" || !rawPath.contains(".")) {
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

                if (headers["connection"]?.equals("close", ignoreCase = true) == true) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Socket loop terminated: ${e.message}")
        } finally {
            if (!keepOpenForSse) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // ── Real AI Inference for Web Access Clients ──

    private fun startInferenceForWebClient(
        chatId: String,
        userPrompt: String,
        assistantNodeIndex: Int
    ) {
        activeGenerations[chatId]?.cancel()

        val generationJob = scope.launch(Dispatchers.IO) {
            val repo = VaultManager.chatRepo ?: run {
                VaultManager.initPlaintext(context)
                VaultManager.chatRepo
            } ?: return@launch
            val assistantMsgId = UUID.randomUUID().toString()
            val assistantNodeId = "node-$assistantMsgId"

            val responseBuffer = StringBuilder()

            // Emit initial empty assistant node
            emitNodeUpdate(
                chatId = chatId,
                nodeId = assistantNodeId,
                nodeIndex = assistantNodeIndex,
                messageId = assistantMsgId,
                role = "assistant",
                text = "",
                isGenerating = true
            )

            val existingMsgs = repo.getMessagesForChat(chatId)

            try {
                if (LlmModelWorker.isGgufModelLoaded.value) {
                    // Multi-turn GGUF streaming
                    val messagesJson = JSONArray().apply {
                        for (m in existingMsgs) {
                            put(JSONObject().apply {
                                put("role", if (m.role == Role.User) "user" else "assistant")
                                put("content", m.content.content)
                            })
                        }
                    }.toString()

                    var lastEmitTime = 0L
                    LlmModelWorker.ggufGenerateMultiTurnStreaming(messagesJson, maxTokens = 2048).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                responseBuffer.append(event.text)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 40) {
                                    lastEmitTime = now
                                    emitNodeUpdate(
                                        chatId = chatId,
                                        nodeId = assistantNodeId,
                                        nodeIndex = assistantNodeIndex,
                                        messageId = assistantMsgId,
                                        role = "assistant",
                                        text = responseBuffer.toString(),
                                        isGenerating = true
                                    )
                                }
                            }
                            is GenerationEvent.Done -> {
                                val finalText = responseBuffer.toString()
                                val assistantMsg = Messages(
                                    msgId = assistantMsgId,
                                    role = Role.Assistant,
                                    content = MessageContent(contentType = ContentType.Text, content = finalText),
                                    timestamp = System.currentTimeMillis()
                                )
                                repo.addMessage(chatId, assistantMsg)

                                emitNodeUpdate(
                                    chatId = chatId,
                                    nodeId = assistantNodeId,
                                    nodeIndex = assistantNodeIndex,
                                    messageId = assistantMsgId,
                                    role = "assistant",
                                    text = finalText,
                                    isGenerating = false
                                )
                                notifyConversationListChanged()
                            }
                            is GenerationEvent.Error -> {
                                responseBuffer.append("\n\n[Error: ${event.message}]")
                                val finalText = responseBuffer.toString()
                                val assistantMsg = Messages(
                                    msgId = assistantMsgId,
                                    role = Role.Assistant,
                                    content = MessageContent(contentType = ContentType.Text, content = finalText),
                                    timestamp = System.currentTimeMillis()
                                )
                                repo.addMessage(chatId, assistantMsg)

                                emitNodeUpdate(
                                    chatId = chatId,
                                    nodeId = assistantNodeId,
                                    nodeIndex = assistantNodeIndex,
                                    messageId = assistantMsgId,
                                    role = "assistant",
                                    text = finalText,
                                    isGenerating = false
                                )
                                notifyConversationListChanged()
                            }
                            else -> {}
                        }
                    }
                } else {
                    // Fallback when no model is loaded
                    val fallbackText = "No on-device GGUF model is currently loaded in RAM on your Android phone.\n\nPlease open the BIT app on your phone and load a local model to start chatting."
                    val assistantMsg = Messages(
                        msgId = assistantMsgId,
                        role = Role.Assistant,
                        content = MessageContent(contentType = ContentType.Text, content = fallbackText),
                        timestamp = System.currentTimeMillis()
                    )
                    repo.addMessage(chatId, assistantMsg)

                    emitNodeUpdate(
                        chatId = chatId,
                        nodeId = assistantNodeId,
                        nodeIndex = assistantNodeIndex,
                        messageId = assistantMsgId,
                        role = "assistant",
                        text = fallbackText,
                        isGenerating = false
                    )
                    notifyConversationListChanged()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error in web client handler", e)
                val errText = responseBuffer.toString() + "\n\n[Generation interrupted: ${e.message}]"
                val assistantMsg = Messages(
                    msgId = assistantMsgId,
                    role = Role.Assistant,
                    content = MessageContent(contentType = ContentType.Text, content = errText),
                    timestamp = System.currentTimeMillis()
                )
                repo.addMessage(chatId, assistantMsg)

                emitNodeUpdate(
                    chatId = chatId,
                    nodeId = assistantNodeId,
                    nodeIndex = assistantNodeIndex,
                    messageId = assistantMsgId,
                    role = "assistant",
                    text = errText,
                    isGenerating = false
                )
                notifyConversationListChanged()
            } finally {
                activeGenerations.remove(chatId)
            }
        }

        activeGenerations[chatId] = generationJob
    }

    private fun emitNodeUpdate(
        chatId: String,
        nodeId: String,
        nodeIndex: Int,
        messageId: String,
        role: String = "assistant",
        text: String,
        isGenerating: Boolean
    ) {
        val clients = conversationSseClients[chatId] ?: return
        if (clients.isEmpty()) return

        val msgDto = JSONObject().apply {
            put("id", messageId)
            put("role", role)
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("createdAt", formatIsoTimestamp(System.currentTimeMillis()))
            put("modelId", LlmModelWorker.currentGgufModelId.value ?: "default")
        }

        val nodeDto = JSONObject().apply {
            put("id", nodeId)
            put("messages", JSONArray().apply { put(msgDto) })
            put("selectIndex", 0)
        }

        val updateEvent = JSONObject().apply {
            put("type", "node_update")
            put("seq", sequenceCounter.incrementAndGet())
            put("conversationId", chatId)
            put("nodeId", nodeId)
            put("nodeIndex", nodeIndex)
            put("node", nodeDto)
            put("updateAt", System.currentTimeMillis())
            put("isGenerating", isGenerating)
            put("serverTime", System.currentTimeMillis())
        }

        val payload = "event: node_update\ndata: $updateEvent\n\n"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)

        val deadClients = mutableListOf<OutputStream>()
        for (client in clients) {
            try {
                client.write(bytes)
                client.flush()
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }
        clients.removeAll(deadClients)
    }

    private fun notifyConversationListChanged() {
        val eventObj = JSONObject().apply {
            put("type", "invalidate")
            put("assistantId", "default")
            put("timestamp", System.currentTimeMillis())
        }
        val payload = "event: invalidate\ndata: $eventObj\n\n"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val deadClients = mutableListOf<OutputStream>()
        for (client in conversationListSseClients) {
            try {
                client.write(bytes)
                client.flush()
            } catch (_: Exception) {
                deadClients.add(client)
            }
        }
        conversationListSseClients.removeAll(deadClients)
    }

    private fun startSseStream(output: OutputStream) {
        val sseHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=UTF-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(sseHeader.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun serveAssetFile(output: OutputStream, assetPath: String) {
        try {
            var bytes = context.assets.open(assetPath).use { it.readBytes() }

            // Inject __LASTCHAT_WEB_BOOT__ into index.html
            if (assetPath == "index.html" || assetPath.endsWith(".html")) {
                var htmlStr = String(bytes, StandardCharsets.UTF_8)
                if (!htmlStr.contains("__LASTCHAT_WEB_BOOT__")) {
                    val bootScript = "<script>window.__LASTCHAT_WEB_BOOT__ = {\"authRequired\": false};</script>"
                    htmlStr = if (htmlStr.contains("<head>")) {
                        htmlStr.replace("<head>", "<head>$bootScript")
                    } else {
                        bootScript + htmlStr
                    }
                    bytes = htmlStr.toByteArray(StandardCharsets.UTF_8)
                }
            }

            val mimeType = guessMimeType(assetPath)
            val cacheControl = if (assetPath.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache"

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mimeType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: $cacheControl\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: keep-alive\r\n\r\n"

            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {
            // SPA routing fallback
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
            val p = it.split("=")
            if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8")
            else URLDecoder.decode(p[0], "UTF-8") to ""
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
                "Connection: keep-alive\r\n\r\n"
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
                "Connection: keep-alive\r\n\r\n"
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
