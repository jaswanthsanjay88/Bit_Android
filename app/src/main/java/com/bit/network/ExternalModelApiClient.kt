package com.bit.network

import com.bit.models.data.HFModelRepository
import com.google.gson.JsonElement
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ExternalModelApiClient {

    private val services = ConcurrentHashMap<String, ExternalModelApiService>()

    private fun buildService(baseUrl: String): ExternalModelApiService {
        val normalized = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        return services.getOrPut(normalized) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ExternalModelApiService::class.java)
        }
    }

    suspend fun fetchCatalog(repo: HFModelRepository): Response<JsonElement> {
        val endpoint = repo.apiEndpoint.trim().ifBlank { "/api/v1/models" }
        val relative = endpoint.removePrefix("/")
        val authHeader = formatAuthHeader(repo.apiAuthToken)
        return buildService(repo.apiBaseUrl.trim()).getCatalog(relative, authHeader)
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String?, endpoint: String = "models"): List<String> {
        val authHeader = formatAuthHeader(apiKey)
        val normalizedBase = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        val service = buildService(normalizedBase)
        val response = service.getCatalog(endpoint, authHeader)
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch models: ${response.code()} ${response.message()}")
        }
        val json = response.body() ?: throw Exception("Empty response body")
        
        val list = mutableListOf<String>()
        try {
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                if (obj.has("data") && obj.get("data").isJsonArray) {
                    val data = obj.getAsJsonArray("data")
                    for (elem in data) {
                        if (elem.isJsonObject) {
                            val modelObj = elem.asJsonObject
                            if (modelObj.has("id")) {
                                list.add(modelObj.get("id").asString)
                            }
                        }
                    }
                } else if (obj.has("models") && obj.get("models").isJsonArray) {
                    val models = obj.getAsJsonArray("models")
                    for (elem in models) {
                        if (elem.isJsonObject) {
                            val modelObj = elem.asJsonObject
                            if (modelObj.has("name")) {
                                val name = modelObj.get("name").asString
                                list.add(name.removePrefix("models/"))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ExternalModelApi", "Failed to parse models json", e)
        }
        return list
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

