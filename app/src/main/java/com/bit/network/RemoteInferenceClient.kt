package com.bit.network

import com.dark.gguf_lib.toolcalling.ToolCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RemoteInferenceClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class InferenceResult(
        val text: String,
        val raw: String,
        val toolCalls: List<ToolCall>? = null
    )

    data class Message(
        val role: String,
        val content: String,
        val base64Image: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null
    )

    fun infer(
        endpoint: String,
        model: String,
        prompt: String,
        stream: Boolean = false,
        authHeader: String? = null,
        tools: org.json.JSONArray? = null,
        maxTokens: Int? = null
    ): InferenceResult = inferWithHistory(
        endpoint, model, listOf(Message("user", prompt)), stream, authHeader, tools, maxTokens
    )

    fun inferWithHistory(
        endpoint: String,
        model: String,
        messages: List<Message>,
        stream: Boolean = false,
        authHeader: String? = null,
        tools: org.json.JSONArray? = null,
        maxTokens: Int? = null
    ): InferenceResult {
        val normalizedEndpoint = endpoint.trim().lowercase()
        val isChatEndpoint = normalizedEndpoint.contains("/api/chat") ||
            normalizedEndpoint.contains("/v1/chat/completions")

        val history = messages.toMutableList()
        var currentLoop = 0
        val maxRounds = 5
        var lastExtractedText = ""
        var lastRawBody = ""
        var lastToolCalls: List<ToolCall>? = null

        while (currentLoop < maxRounds) {
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", false) // Force non-streaming response for synchronous HTTP clients
                val safeMaxTokens = maxTokens?.takeIf { it > 0 } ?: 8192
                put("max_tokens", safeMaxTokens)
                
                val toolsToUse = tools ?: buildDefaultTools()
                if (toolsToUse.length() > 0) {
                    put("tools", toolsToUse)
                }

                if (isChatEndpoint) {
                    val msgArray = org.json.JSONArray()
                    history.forEach { msg ->
                        val msgObj = JSONObject().put("role", msg.role)
                        if (msg.base64Image != null) {
                            val contentArray = org.json.JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", msg.content))
                                put(JSONObject().put("type", "image_url").put("image_url", 
                                    JSONObject().put("url", "data:image/jpeg;base64,${msg.base64Image}")
                                ))
                            }
                            msgObj.put("content", contentArray)
                        } else {
                            msgObj.put("content", msg.content)
                        }

                        if (!msg.toolCallId.isNullOrEmpty()) {
                            msgObj.put("tool_call_id", msg.toolCallId)
                        }

                        if (!msg.toolCalls.isNullOrEmpty()) {
                            val toolCallsJson = org.json.JSONArray()
                            msg.toolCalls.forEach { tc ->
                                val toolId = buildToolCallId(tc.name, tc.arguments.toString())
                                val toolCallObj = JSONObject().apply {
                                    put("id", toolId)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments.toString())
                                    })
                                }
                                toolCallsJson.put(toolCallObj)
                            }
                            msgObj.put("tool_calls", toolCallsJson)
                        }
                        msgArray.put(msgObj)
                    }
                    put("messages", msgArray)
                } else {
                    val lastMsg = history.lastOrNull()
                    if (lastMsg?.base64Image != null) {
                        put("prompt", lastMsg.content)
                        put("images", org.json.JSONArray().put(lastMsg.base64Image))
                    } else {
                        put("prompt", lastMsg?.content ?: "")
                    }
                }
            }

            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")

            formatAuthHeader(authHeader)?.let { requestBuilder.header("Authorization", it) }

            val request = requestBuilder.build()

            val responseBody = try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        val bodyLower = body.lowercase()
                        if (response.code == 403 && (
                                bodyLower.contains("just a moment") ||
                                bodyLower.contains("cloudflare") ||
                                bodyLower.contains("__cf_chl")
                            )
                        ) {
                            throw IllegalStateException(
                                "Remote API is protected by Cloudflare challenge (JS/cookie). " +
                                    "Allow direct API access for this path (disable challenge for /api/* or use a non-challenged API subdomain)."
                            )
                        }
                        throw IllegalStateException("Remote inference failed (${response.code}) for $endpoint: ${body.take(400)}")
                    }
                    body
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException) {
                    throw IllegalStateException("Unable to resolve host: ${e.message}. Please check your internet connection and API endpoint URL ($endpoint).")
                }
                throw e
            }

            lastRawBody = responseBody
            lastExtractedText = extractText(responseBody)

            var toolCallsList = parseNativeToolCalls(responseBody)
            if (toolCallsList.isNullOrEmpty()) {
                toolCallsList = parseFallbackToolCalls(lastExtractedText)
            }

            lastToolCalls = toolCallsList

            if (toolCallsList.isNullOrEmpty()) {
                break
            }

            history.add(Message(
                role = "assistant",
                content = filterToolCallSyntax(lastExtractedText),
                toolCalls = toolCallsList
            ))

            toolCallsList.forEach { tc ->
                val resultText = when (tc.name) {
                    "web_search" -> {
                        val query = tc.arguments.optString("query")
                        val numResults = tc.arguments.optInt("num_results", 5)
                        executeWebSearch(query, numResults)
                    }
                    "web_fetch" -> {
                        val url = tc.arguments.optString("url")
                        val maxChars = tc.arguments.optInt("maxChars", 100000)
                        executeWebFetch(url, maxChars)
                    }
                    else -> {
                        JSONObject().apply {
                            put("error", "unknown_tool")
                            put("message", "Unknown tool: ${tc.name}")
                        }.toString()
                    }
                }

                val toolId = buildToolCallId(tc.name, tc.arguments.toString())
                history.add(Message(
                    role = "tool",
                    content = resultText,
                    toolCallId = toolId
                ))
            }

            currentLoop++
        }

        return InferenceResult(text = lastExtractedText, raw = lastRawBody, toolCalls = lastToolCalls)
    }

    private fun buildToolCallId(toolName: String, arguments: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val input = "$toolName:$arguments"
        val hash = digest.digest(input.toByteArray())
        val shortHash = hash.take(8).joinToString("") { "%02x".format(it) }
        return "call_${toolName}_$shortHash"
    }

    private fun buildDefaultTools(): org.json.JSONArray {
        return org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "web_search")
                    put("description", "Search the web for current information and scrape the top results. Use this to find facts, news, or data not in your training set.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string")
                                put("description", "The search query to execute.")
                            })
                            put("num_results", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Number of results to return (1-10, default 5).")
                            })
                        })
                        put("required", org.json.JSONArray().put("query"))
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "web_fetch")
                    put("description", "Fetch and read the full text content of a web page. Use this when you need more detail from a specific page.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("url", JSONObject().apply {
                                put("type", "string")
                                put("description", "The URL of the page to fetch.")
                            })
                            put("maxChars", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Maximum characters of text to return (default 100000, max 1000000).")
                            })
                        })
                        put("required", org.json.JSONArray().put("url"))
                    })
                })
            })
        }
    }

    private fun parseNativeToolCalls(body: String): List<ToolCall>? {
        try {
            val obj = JSONObject(body.trim())
            val choices = obj.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)
                val messageObj = first?.optJSONObject("message")
                val toolCallsJson = messageObj?.optJSONArray("tool_calls")
                if (toolCallsJson != null && toolCallsJson.length() > 0) {
                    val list = mutableListOf<ToolCall>()
                    for (i in 0 until toolCallsJson.length()) {
                        val call = toolCallsJson.optJSONObject(i)
                        val func = call?.optJSONObject("function")
                        val name = func?.optString("name")
                        val args = func?.optString("arguments")
                        if (!name.isNullOrEmpty() && args != null) {
                            list.add(ToolCall(name, JSONObject(args)))
                        }
                    }
                    if (list.isNotEmpty()) {
                        return list
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun parseFallbackToolCalls(content: String): List<ToolCall>? {
        if (content.isBlank()) return null
        val list = mutableListOf<ToolCall>()

        try {
            val toolCallXmlRegex = Regex(
                "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>",
                RegexOption.DOT_MATCHES_ALL
            )
            toolCallXmlRegex.findAll(content).forEach { match ->
                val jsonStr = match.groupValues[1]
                val json = JSONObject(jsonStr)
                val name = json.optString("name")
                val args = json.optJSONObject("arguments") ?: JSONObject()
                if (!name.isNullOrEmpty()) {
                    list.add(ToolCall(name, args))
                }
            }
            if (list.isNotEmpty()) return list

            val toolCallsJsonRegex = Regex(
                "\\{\\s*\"tool_calls\"\\s*:\\s*\\[.*?\\]\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            )
            val jsonMatch = toolCallsJsonRegex.find(content)
            if (jsonMatch != null) {
                val json = JSONObject(jsonMatch.value)
                val toolCallsArray = json.optJSONArray("tool_calls")
                if (toolCallsArray != null && toolCallsArray.length() > 0) {
                    for (i in 0 until toolCallsArray.length()) {
                        val call = toolCallsArray.getJSONObject(i)
                        val name = call.optString("name")
                        val argsObj = when (val argsVal = call.opt("arguments")) {
                            is JSONObject -> argsVal
                            is String -> JSONObject(argsVal)
                            else -> JSONObject()
                        }
                        if (!name.isNullOrEmpty()) {
                            list.add(ToolCall(name, argsObj))
                        }
                    }
                }
            }
            if (list.isNotEmpty()) return list

            val directJsonRegex = Regex(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            )
            val directMatch = directJsonRegex.find(content)
            if (directMatch != null) {
                val name = directMatch.groupValues[1]
                val args = JSONObject(directMatch.groupValues[2])
                list.add(ToolCall(name, args))
            }
            if (list.isNotEmpty()) return list
        } catch (_: Exception) {}

        return null
    }

    private fun filterToolCallSyntax(content: String): String {
        var filtered = content
        filtered = filtered.replace(Regex("<tool_call>\\s*\\{.*?\\}\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("```json\\s*\\{[^`]*```", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("```\\s*\\{[^`]*```", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("\\{\\s*\"tool_calls\"\\s*:[^}]*\\}\\s*", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{.*?\\}\\s*\\}", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.trim()
        filtered = filtered.replace(Regex("\\n{3,}"), "\n\n")
        return filtered
    }

    private fun executeWebSearch(query: String, numResults: Int): String {
        val count = numResults.coerceIn(1, 10)
        val scraper = DuckDuckGoScraper(client)
        return when (val r = scraper.search(query, count)) {
            is DuckDuckGoScraper.SearchResponse.Success -> {
                val results = r.results
                val finalResults = org.json.JSONArray()
                results.forEach { res ->
                    finalResults.put(JSONObject().apply {
                        put("title", res.title)
                        put("url", res.url)
                        put("description", res.snippet)
                    })
                }

                JSONObject().apply {
                    put("type", "web_search")
                    put("query", query)
                    put("results", finalResults)
                }.toString()
            }
            is DuckDuckGoScraper.SearchResponse.Error -> {
                JSONObject().apply {
                    put("type", "web_search")
                    put("query", query)
                    put("error", r.type.name.lowercase())
                    put("message", r.message)
                }.toString()
            }
        }
    }

    private fun executeWebFetch(url: String, maxChars: Int): String {
        val limit = maxChars.coerceIn(1, 10000000)
        return try {
            val content = fetchAndCleanUrl(url)
            val truncated = if (content.length > limit) content.take(limit) + "... [truncated]" else content
            JSONObject().apply {
                put("type", "web_fetch")
                put("url", url)
                put("text", truncated)
                put("truncated", content.length > limit)
                put("totalChars", content.length)
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private fun fetchAndCleanUrl(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return "Failed to fetch: HTTP ${response.code}"
            }
            val html = response.body.string()
            return cleanHtml(html)
        }
    }

    private fun cleanHtml(html: String): String {
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("script, style, noscript, svg, head, nav, header, footer, aside, iframe").remove()
        return doc.text()
            .replace(Regex("[\\p{Cc}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        return try {
            val obj = JSONObject(trimmed)

            val choices = obj.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)
                val fromMessage = first?.optJSONObject("message")?.optString("content")
                if (!fromMessage.isNullOrBlank()) return fromMessage
                val fromText = first?.optString("text")
                if (!fromText.isNullOrBlank()) return fromText
            }

            val chatMessage = obj.optJSONObject("message")?.optString("content")
            if (!chatMessage.isNullOrBlank()) return chatMessage

            obj.optString("response").takeIf { it.isNotBlank() }
                ?: obj.optString("text").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("output").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("data")?.optString("response")?.takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("data")?.optString("text")?.takeIf { it.isNotBlank() }
                ?: trimmed
        } catch (_: Exception) {
            trimmed
        }
    }

    data class EmbeddingResult(
        val vector: List<Double>
    )

    fun getEmbedding(
        endpoint: String,
        model: String,
        text: String,
        authHeader: String? = null
    ): EmbeddingResult {
        val embeddingEndpoint = endpoint.trim().removeSuffix("/")
            .replaceFirst(Regex("/api/chat|/api/generate"), "/api/embeddings")
        if (embeddingEndpoint == endpoint.trim()) {
            throw IllegalStateException("Cannot derive embeddings endpoint from $endpoint")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("prompt", text)
        }

        val requestBuilder = Request.Builder()
            .url(embeddingEndpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")

        formatAuthHeader(authHeader)?.let { requestBuilder.header("Authorization", it) }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("Embedding request failed (${response.code}): ${body.take(400)}")
            }

            return try {
                val obj = JSONObject(body)
                val embedding = obj.optJSONArray("embedding")
                    ?: obj.optJSONArray("embeddings")?.optJSONArray(0)
                    ?: throw IllegalStateException("No embedding array in response")

                val vector = mutableListOf<Double>()
                for (i in 0 until embedding.length()) {
                    vector.add(embedding.getDouble(i))
                }
                EmbeddingResult(vector)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to parse embedding: ${e.message}")
            }
        }
    }

    private fun formatAuthHeader(token: String?): String? {
        val trimmed = token?.trim() ?: return null
        if (trimmed.isBlank()) return null
        if (trimmed.contains(Regex("^[a-zA-Z]+\\s+"))) {
            return trimmed
        }
        return "Bearer $trimmed"
    }
}
