package com.bit.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RemoteInferenceClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class InferenceResult(
        val text: String,
        val raw: String
    )

    data class Message(
        val role: String,
        val content: String
    )

    fun infer(
        endpoint: String,
        model: String,
        prompt: String,
        stream: Boolean = false,
        authHeader: String? = null
    ): InferenceResult = inferWithHistory(
        endpoint, model, listOf(Message("user", prompt)), stream, authHeader
    )

    fun inferWithHistory(
        endpoint: String,
        model: String,
        messages: List<Message>,
        stream: Boolean = false,
        authHeader: String? = null
    ): InferenceResult {
        val normalizedEndpoint = endpoint.trim().lowercase()
        val isChatEndpoint = normalizedEndpoint.contains("/api/chat") ||
            normalizedEndpoint.contains("/v1/chat/completions")

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", stream)

            if (isChatEndpoint) {
                val msgArray = org.json.JSONArray()
                messages.forEach { msg ->
                    msgArray.put(
                        JSONObject()
                            .put("role", msg.role)
                            .put("content", msg.content)
                    )
                }
                put("messages", msgArray)
            } else {
                val lastMsg = messages.lastOrNull()?.content ?: ""
                put("prompt", lastMsg)
            }
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")

        authHeader?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", it) }

        val request = requestBuilder.build()

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
                throw IllegalStateException("Remote inference failed (${response.code}): ${body.take(400)}")
            }

            val extracted = extractText(body)
            return InferenceResult(text = extracted, raw = body)
        }
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

        authHeader?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Authorization", it) }

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
}
