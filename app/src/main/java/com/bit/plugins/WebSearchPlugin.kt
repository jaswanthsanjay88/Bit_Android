package com.bit.plugins

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.bit.network.DuckDuckGoScraper
import com.bit.network.HttpClient
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

import android.content.Context
import kotlinx.coroutines.flow.first
import org.json.JSONArray

class WebSearchPlugin(private val context: Context) : SuperPlugin {

    companion object {
        private const val TAG = "WebSearchPlugin"
        const val TOOL_WEB_SEARCH = "web_search"
        const val TOOL_WEB_FETCH = "web_fetch"

        private const val WEB_FETCH_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_WEB_FETCH_HTML_LENGTH = 600_000
    }

    override fun getPluginInfo(): PluginInfo {
        return PluginInfo(
            name = "Web Search",
            description = "Search the web and fetch full page contents for LLM grounding",
            author = "BIT",
            version = "3.0.0",
            toolDefinitionBuilder = listOf(
                ToolDefinitionBuilder(
                    TOOL_WEB_SEARCH,
                    "Search the web for current information. Use this to find facts, news, or data not in your training set."
                )
                    .stringParam("query", "The search query to execute", required = true)
                    .numberParam("num_results", "Number of results to return (1-10, default 5)", required = false),
                ToolDefinitionBuilder(
                    TOOL_WEB_FETCH,
                    "Fetch and read the full text content of a web page. Use this after web_search when you need more detail from a specific page."
                )
                    .stringParam("url", "The URL of the page to fetch", required = true)
                    .numberParam("maxChars", "Maximum characters of text to return (default 8000, max 100000)", required = false)
            )
        )
    }

    override fun serializeResult(data: Any): String = when (data) {
        is WebSearchResponse -> data.toJSON().toString()
        is WebFetchResponse -> data.toJSON().toString()
        is JSONObject -> data.toString()
        else -> data.toString()
    }

    override suspend fun executeTool(toolCall: ToolCall): Result<Any> {
        return try {
            when (toolCall.name) {
                TOOL_WEB_SEARCH -> executeSearch(toolCall)
                TOOL_WEB_FETCH -> executeFetch(toolCall)
                else -> Result.failure(IllegalArgumentException("Unknown tool: ${toolCall.name}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeSearch(toolCall: ToolCall): Result<Any> = withContext(Dispatchers.IO) {
        val query = toolCall.getString("query")
        val numResults = toolCall.getInt("num_results", 5).coerceIn(1, 10)
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Search: '$query' (max $numResults)")

        val settings = com.bit.data.AppSettingsDataStore(context)
        val provider = settings.webSearchProvider.first()
        val apiKey = settings.webSearchApiKey.first()
        val baseUrl = settings.webSearchBaseUrl.first()

        try {
            if (provider == "duckduckgo") {
                val scraper = DuckDuckGoScraper()
                val response = when (val r = scraper.search(query, numResults)) {
                    is DuckDuckGoScraper.SearchResponse.Success -> {
                        val results = r.results.mapIndexed { index, webResult ->
                            WebSearchResult(
                                title = webResult.title,
                                url = webResult.url,
                                snippet = webResult.snippet,
                                content = "",
                                domain = extractDomain(webResult.url),
                                scraped = false,
                                index = index
                            )
                        }
                        WebSearchResponse(
                            query = query,
                            results = results,
                            totalResults = results.size,
                            searchTimeMs = System.currentTimeMillis() - startTime,
                            status = "SUCCESS",
                            provider = "duckduckgo"
                        )
                    }
                    is DuckDuckGoScraper.SearchResponse.Error -> {
                        WebSearchResponse(
                            query = query,
                            results = emptyList(),
                            totalResults = 0,
                            searchTimeMs = System.currentTimeMillis() - startTime,
                            status = "ERROR",
                            error = r.message,
                            provider = "duckduckgo"
                        )
                    }
                }
                return@withContext Result.success(response.copy(summary = response.generateSummary()))
            }

            if (provider != "searxng" && apiKey.isBlank()) {
                return@withContext Result.success(WebSearchResponse(
                    query = query,
                    results = emptyList(),
                    totalResults = 0,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    status = "ERROR",
                    error = "No API key configured for search provider: $provider",
                    provider = provider
                ).let { it.copy(summary = it.generateSummary()) })
            }

            val body = when (provider) {
                "serper" -> HttpClient.post(
                    "https://google.serper.dev/search",
                    JSONObject().apply {
                        put("q", query)
                        put("num", numResults)
                    }.toString(),
                    mapOf("X-API-KEY" to apiKey)
                )
                "tavily" -> HttpClient.post(
                    "https://api.tavily.com/search",
                    JSONObject().apply {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }.toString(),
                    emptyMap()
                )
                "searxng" -> {
                    val resolvedBase = baseUrl.ifBlank { "https://searx.be" }
                    HttpClient.fetchModels(
                        "$resolvedBase/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&engines=google,brave"
                    )
                }
                else -> HttpClient.fetchModels( // brave
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey)
                )
            }

            if (body == null) {
                return@withContext Result.success(WebSearchResponse(
                    query = query,
                    results = emptyList(),
                    totalResults = 0,
                    searchTimeMs = System.currentTimeMillis() - startTime,
                    status = "ERROR",
                    error = "Search provider response was empty or failed.",
                    provider = provider
                ).let { it.copy(summary = it.generateSummary()) })
            }

            val json = JSONObject(body)
            val resultsList = mutableListOf<WebSearchResult>()

            when (provider) {
                "tavily" -> {
                    val resultsArray = json.optJSONArray("results")
                    if (resultsArray != null) {
                        for (i in 0 until resultsArray.length()) {
                            val obj = resultsArray.getJSONObject(i)
                            resultsList.add(WebSearchResult(
                                title = obj.optString("title"),
                                url = obj.optString("url"),
                                snippet = obj.optString("content"),
                                content = "",
                                domain = extractDomain(obj.optString("url")),
                                index = i
                            ))
                        }
                    }
                }
                "serper" -> {
                    val organic = json.optJSONArray("organic")
                    if (organic != null) {
                        for (i in 0 until organic.length()) {
                            val obj = organic.getJSONObject(i)
                            resultsList.add(WebSearchResult(
                                title = obj.optString("title"),
                                url = obj.optString("link"),
                                snippet = obj.optString("snippet"),
                                content = "",
                                domain = extractDomain(obj.optString("link")),
                                index = i
                            ))
                        }
                    }
                }
                "searxng" -> {
                    val resultsArray = json.optJSONArray("results")
                    if (resultsArray != null) {
                        for (i in 0 until resultsArray.length()) {
                            val obj = resultsArray.getJSONObject(i)
                            resultsList.add(WebSearchResult(
                                title = obj.optString("title"),
                                url = obj.optString("url"),
                                snippet = obj.optString("content"),
                                content = "",
                                domain = extractDomain(obj.optString("url")),
                                index = i
                            ))
                        }
                    }
                }
                else -> { // brave
                    val web = json.optJSONObject("web")
                    val resultsArray = web?.optJSONArray("results")
                    if (resultsArray != null) {
                        for (i in 0 until resultsArray.length()) {
                            val obj = resultsArray.getJSONObject(i)
                            resultsList.add(WebSearchResult(
                                title = obj.optString("title"),
                                url = obj.optString("url"),
                                snippet = obj.optString("description"),
                                content = "",
                                domain = extractDomain(obj.optString("url")),
                                index = i
                            ))
                        }
                    }
                }
            }

            val response = WebSearchResponse(
                query = query,
                results = resultsList,
                totalResults = resultsList.size,
                searchTimeMs = System.currentTimeMillis() - startTime,
                status = "SUCCESS",
                provider = provider
            )
            Result.success(response.copy(summary = response.generateSummary()))
        } catch (e: Exception) {
            Log.e(TAG, "Search execution error: ${e.message}", e)
            Result.success(WebSearchResponse(
                query = query,
                results = emptyList(),
                totalResults = 0,
                searchTimeMs = System.currentTimeMillis() - startTime,
                status = "ERROR",
                error = e.message ?: "Unknown search error",
                provider = provider
            ).let { it.copy(summary = it.generateSummary()) })
        }
    }

    private suspend fun executeFetch(toolCall: ToolCall): Result<Any> = withContext(Dispatchers.IO) {
        val url = toolCall.getString("url")
        val maxChars = toolCall.getInt("maxChars", 8000).coerceIn(1, 100_000)

        Log.d(TAG, "Fetch URL: '$url' (maxChars $maxChars)")

        try {
            val html = HttpClient.fetchModels(url, mapOf(
                "User-Agent" to WEB_FETCH_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*"
            ))
            if (html == null) {
                return@withContext Result.success(WebFetchResponse(
                    url = url,
                    text = "",
                    truncated = false,
                    totalChars = 0,
                    error = "No response from server or request failed."
                ))
            }

            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)

            Result.success(WebFetchResponse(
                url = url,
                text = text,
                truncated = fullText.length > text.length,
                totalChars = fullText.length
            ))
        } catch (e: Exception) {
            Result.success(WebFetchResponse(
                url = url,
                text = "",
                truncated = false,
                totalChars = 0,
                error = e.message ?: "Unknown fetch error"
            ))
        }
    }

    private fun extractDomain(url: String): String {
        return try { java.net.URL(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
    }

    private fun htmlToReadableText(rawHtml: String): String {
        val stripped = rawHtml
            .take(MAX_WEB_FETCH_HTML_LENGTH)
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(
                Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            .replace(
                Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
        val text = HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // ── UI ──

    @Composable
    override fun ToolCallUI() { }

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        val type = data.optString("type", "")
        if (type == "web_fetch" || data.has("text") || data.has("totalChars")) {
            WebFetchResultUI(data)
        } else if (data.has("query") && data.has("results")) {
            SearchResultsUI(data)
        } else {
            Text(
                text = data.toString(2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }

    @Composable
    private fun WebFetchResultUI(data: JSONObject) {
        val url = data.optString("url", "")
        val text = data.optString("text", "")
        val truncated = data.optBoolean("truncated", false)
        val totalChars = data.optInt("totalChars", 0)
        val error = data.optString("error", "")

        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Fetched: $url",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (error.isNotEmpty()) {
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "Retrieved ${text.length} chars of $totalChars total" + if (truncated) " (truncated)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = text.take(500) + if (text.length > 500) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(6.dp),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    @Composable
    private fun SearchResultsUI(data: JSONObject) {
        val query = data.optString("query", "")
        val resultsArray = data.optJSONArray("results")
        val totalResults = data.optInt("totalResults", 0)
        val searchTimeMs = data.optLong("searchTimeMs", 0)

        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Search: \"$query\"",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$totalResults results · ${searchTimeMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (resultsArray != null && resultsArray.length() > 0) {
                for (i in 0 until resultsArray.length()) {
                    val r = resultsArray.getJSONObject(i)
                    SearchResultCard(
                        title = r.optString("title", ""),
                        snippet = r.optString("snippet", ""),
                        url = r.optString("url", ""),
                        position = i + 1
                    )
                    if (i < resultsArray.length() - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SearchResultCard(title: String, snippet: String, url: String, position: Int) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "#$position",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snippet.isNotBlank()) {
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
