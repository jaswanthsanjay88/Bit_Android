package com.bit.api

import android.util.Base64
import android.util.Log
import com.bit.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object RemoteImageClient {
    private const val TAG = "RemoteImageClient"

    /**
     * Check if a model name indicates an image generation model.
     */
    fun isImageModelName(modelName: String): Boolean {
        val lower = modelName.lowercase()
        return lower.contains("dall-e") ||
               lower.contains("flux") ||
               lower.contains("sdxl") ||
               lower.contains("stable-diffusion") ||
               lower.contains("imagen") ||
               lower.contains("midjourney") ||
               lower.contains("recraft") ||
               lower.contains("ideogram") ||
               lower.contains("playground") ||
               lower.contains("diffusion") ||
               lower.contains("image")
    }

    /**
     * Resolve the image generation endpoint from the provider's base endpoint.
     */
    fun resolveImageEndpoint(baseUrl: String, model: String): String {
        val clean = LlmProviderResolver.cleanBaseUrl(baseUrl).trimEnd('/')
        return when {
            clean.contains("googleapis.com") || clean.contains("google") -> {
                "$clean/models/$model:predict"
            }
            clean.endsWith("/images/generations") -> clean
            clean.endsWith("/v1") -> "$clean/images/generations"
            clean.contains("/v1/") -> {
                val prefix = clean.substringBefore("/v1/")
                "$prefix/v1/images/generations"
            }
            else -> "$clean/v1/images/generations"
        }
    }

    /**
     * Parse error messages from API response body.
     */
    private fun parseErrorMessage(resBody: String, statusCode: Int): String {
        return try {
            val json = JSONObject(resBody)
            if (json.has("error")) {
                val err = json.opt("error")
                if (err is JSONObject) {
                    err.optString("message", "HTTP $statusCode")
                } else {
                    err?.toString() ?: "HTTP $statusCode"
                }
            } else if (json.has("message")) {
                json.getString("message")
            } else {
                "HTTP $statusCode: $resBody"
            }
        } catch (_: Exception) {
            "HTTP $statusCode: $resBody"
        }
    }

    /**
     * Generate an image via remote API and return the Base64-encoded image string.
     */
    suspend fun generateImage(
        endpointUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        size: String = "1024x1024"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val isGoogle = endpointUrl.contains("googleapis.com") || endpointUrl.contains("google") || modelName.contains("imagen")
            val cleanKey = LlmProviderResolver.cleanApiKey(apiKey)

            if (isGoogle) {
                val url = resolveImageEndpoint(endpointUrl, modelName) + if (cleanKey.isNotBlank()) "?key=$cleanKey" else ""
                val bodyJson = JSONObject().apply {
                    put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
                    put("parameters", JSONObject().put("sampleCount", 1))
                }
                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = HttpClient.client.newCall(request).execute()
                val resBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = parseErrorMessage(resBody, response.code)
                    return@withContext Result.failure(Exception(errMsg))
                }
                val json = JSONObject(resBody)
                val predictions = json.optJSONArray("predictions")
                val b64 = predictions?.optJSONObject(0)?.optString("bytesBase64Encoded")
                if (!b64.isNullOrBlank()) {
                    return@withContext Result.success(b64)
                }
                return@withContext Result.failure(Exception("No image returned by Gemini Imagen"))
            } else {
                val targetUrl = resolveImageEndpoint(endpointUrl, modelName)
                val bodyJson = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", prompt)
                    put("n", 1)
                    put("size", size)
                    put("response_format", "b64_json")
                }
                val reqBuilder = Request.Builder()
                    .url(targetUrl)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))

                if (cleanKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $cleanKey")
                }
                if (targetUrl.contains("openrouter")) {
                    reqBuilder.header("HTTP-Referer", "https://github.com/jaswanthsanjay88/Bit_Android")
                    reqBuilder.header("X-Title", "BIT Android")
                }

                val response = HttpClient.client.newCall(reqBuilder.build()).execute()
                val resBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = parseErrorMessage(resBody, response.code)
                    return@withContext Result.failure(Exception(errMsg))
                }

                val json = JSONObject(resBody)
                val data = json.optJSONArray("data")
                val firstItem = data?.optJSONObject(0)
                val b64 = firstItem?.optString("b64_json")
                if (!b64.isNullOrBlank()) {
                    return@withContext Result.success(b64)
                }
                val url = firstItem?.optString("url")
                if (!url.isNullOrBlank()) {
                    val imgReq = Request.Builder().url(url).build()
                    val imgRes = HttpClient.client.newCall(imgReq).execute()
                    val imgBytes = imgRes.body?.bytes()
                    if (imgBytes != null && imgBytes.isNotEmpty()) {
                        val base64 = Base64.encodeToString(imgBytes, Base64.NO_WRAP)
                        return@withContext Result.success(base64)
                    }
                }
                return@withContext Result.failure(Exception("No image data found in provider response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating remote image: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Test connection to a remote image generation model.
     */
    suspend fun testConnection(
        endpointUrl: String,
        apiKey: String,
        modelName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = generateImage(
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                modelName = modelName,
                prompt = "A red dot on white background",
                size = "256x256"
            )
            if (result.isSuccess) {
                Result.success("Success! Image provider connection OK.")
            } else {
                val err = result.exceptionOrNull()
                Result.failure(err ?: Exception("Image endpoint check failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
