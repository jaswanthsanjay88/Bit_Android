package com.bit.plugins.services

import android.util.Log
import com.bit.models.plugins.DuckDuckGoSearchResponse
import com.bit.models.plugins.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fallback search using Searx (open-source meta-search engine).
 * Searx aggregates results from multiple providers without heavy HTML parsing.
 * More reliable than direct Google scraping and privacy-friendly.
 */
class SearxSearchService {

    companion object {
        private const val TAG = "SearxSearch"
        // Public Searx instances (community-hosted, check availability)
        private val SEARX_INSTANCES = listOf(
            "https://searx.be/search",
            "https://search.piratecare.org/search",
            "https://searx.space/search",
            "https://searx.ru/search"
        )
    }

    private val client = CurlImpersonateHelper.getClient(CurlImpersonateHelper.BrowserType.CHROME, timeoutSeconds = 15)

    private val userAgents = listOf(
        "Mozilla/5.0 (Android 14; Mobile; rv:123.0) Gecko/123.0 Firefox/123.0",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    )

    /**
     * Search using Searx API (more reliable than HTML scraping).
     * Returns results in DuckDuckGoSearchResponse format for compatibility.
     */
    suspend fun search(
        query: String,
        maxResults: Int = 5,
        safeSearch: Boolean = true
    ): Result<DuckDuckGoSearchResponse> = withContext(Dispatchers.IO) {
        val sanitized = query.trim().takeIf { it.isNotBlank() } ?: return@withContext Result.failure(
            IllegalArgumentException("Query cannot be empty")
        )

        // Try each Searx instance until one succeeds
        for (baseUrl in SEARX_INSTANCES) {
            try {
                val result = querySearxInstance(baseUrl, sanitized, maxResults, safeSearch)
                if (result.isSuccess) {
                    Log.d(TAG, "Searx search succeeded with $baseUrl")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Searx instance failed ($baseUrl): ${e.message}")
                continue
            }
        }

        Result.failure(IOException("All Searx instances failed for: $sanitized"))
    }

    private suspend fun querySearxInstance(
        baseUrl: String,
        query: String,
        maxResults: Int,
        safeSearch: Boolean
    ): Result<DuckDuckGoSearchResponse> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val encoded = URLEncoder.encode(query, "UTF-8")

            // Searx JSON API endpoint
            val url = buildString {
                append(baseUrl)
                append("?q=").append(encoded)
                append("&format=json")
                append("&pageno=1")
                append("&results=").append(maxResults)
                append("&language=en-US")
                if (safeSearch) append("&safesearch=1")
            }

            val profile = CurlImpersonateHelper.getRandomProfile()
            val requestBuilder = Request.Builder().url(url)
            CurlImpersonateHelper.applyProfileHeaders(requestBuilder, url, profile)
            requestBuilder.header("Accept", "application/json")
            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return@withContext Result.failure(IOException("Rate limited by Searx"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }

                val jsonBody = response.body.string()
                if (jsonBody.isBlank()) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }

                val json = JSONObject(jsonBody)
                val results = mutableListOf<SearchResult>()

                // Parse Searx JSON results
                val resultsArray: Any? = json.optJSONArray("results") ?: emptyList<SearchResult>()

                if (resultsArray is org.json.JSONArray) {
                    for (i in 0 until minOf(resultsArray.length(), maxResults)) {
                        val item = resultsArray.optJSONObject(i) ?: continue
                        
                        val title = item.optString("title", "").takeIf { it.isNotBlank() } ?: continue
                        val url = item.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                        val content = item.optString("content", "")

                        results.add(
                            SearchResult(
                                title = title,
                                snippet = content.take(200),
                                url = url,
                                position = i + 1
                            )
                        )
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime

                return@withContext if (results.isNotEmpty()) {
                    Result.success(
                        DuckDuckGoSearchResponse(
                            query = query,
                            results = results,
                            totalResults = results.size,
                            searchTime = elapsed
                        )
                    )
                } else {
                    Result.failure(IOException("No results parsed from Searx response"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
