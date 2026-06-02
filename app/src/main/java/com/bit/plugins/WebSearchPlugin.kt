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
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.bit.plugins.services.WebScrapingSearchService
import com.bit.plugins.services.WebScrapingService
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// ── Plugin ──

class WebSearchPlugin : SuperPlugin {

    private val ddgHtmlSearchService = WebScrapingSearchService()
    private val scrapingService = WebScrapingService()

    companion object {
        private const val TAG = "WebSearchPlugin"
        const val TOOL_WEB_SEARCH = "web_search"
        private const val SCRAPE_TIMEOUT_MS = 10_000L
        private const val MAX_SCRAPE_CHARS = 1500
    }

    override fun getPluginInfo(): PluginInfo {
        return PluginInfo(
            name = "Web Search",
            description = "Search the web with automatic content scraping from top results",
            author = "BIT",
            version = "2.0.0",
            toolDefinitionBuilder = listOf(
                ToolDefinitionBuilder(
                    TOOL_WEB_SEARCH,
                    "Search the web and automatically scrape content from top results. Returns search results with scraped page content."
                )
                    .stringParam("query", "The search query", required = true)
                    .numberParam("max_results", "Number of results to scrape (1-5, default 3)", required = false)
            )
        )
    }

    // ── Serialization ──

    override fun serializeResult(data: Any): String = when (data) {
        is EnhancedWebSearchPipelineResult -> data.toJSON().toString()
        else -> data.toString()
    }

    // ── Execution ──

    override suspend fun executeTool(toolCall: ToolCall): Result<Any> {
        return try {
            when (toolCall.name) {
                TOOL_WEB_SEARCH -> executePipelinedSearch(toolCall)
                else -> Result.failure(IllegalArgumentException("Unknown tool: ${toolCall.name}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executePipelinedSearch(toolCall: ToolCall): Result<Any> {
        val query = toolCall.getString("query")
        val maxResults = toolCall.getInt("max_results", 3).coerceIn(1, 5)
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "Pipeline search: '$query' (max $maxResults results)")

        // Step 1: DDG HTML search (primary and only provider)
        val searchResult = try {
            ddgHtmlSearchService.search(query, maxResults, safeSearch = true)
        } catch (e: Exception) {
            Log.w(TAG, "DDG HTML search failed: ${e.message}")
            Result.failure(e)
        }

        val provider = "ddg_html"

        // If provider failed
        if (searchResult.isFailure) {
            val e = searchResult.exceptionOrNull()?.let { 
                if (it is Exception) it else Exception(it.message ?: "Search failed", it)
            } ?: Exception("Search failed - all providers unavailable")
            val error = WebSearchError.fromException(e)
            Log.w(TAG, "All search providers failed: ${error.message}")
            val failureResult = EnhancedWebSearchPipelineResult(
                query = query,
                results = emptyList(),
                totalResults = 0,
                searchTimeMs = System.currentTimeMillis() - startTime,
                status = when (error) {
                    is WebSearchError.RateLimitedException -> EnhancedWebSearchPipelineResult.SearchStatus.RATE_LIMITED
                    is WebSearchError.BlockedException -> EnhancedWebSearchPipelineResult.SearchStatus.BLOCKED
                    is WebSearchError.NoResultsException -> EnhancedWebSearchPipelineResult.SearchStatus.NO_RESULTS
                    is WebSearchError.TimeoutException -> EnhancedWebSearchPipelineResult.SearchStatus.TIMEOUT
                    is WebSearchError.NetworkException -> EnhancedWebSearchPipelineResult.SearchStatus.NETWORK_ERROR
                    is WebSearchError.InvalidQueryException -> EnhancedWebSearchPipelineResult.SearchStatus.INVALID_QUERY
                    else -> EnhancedWebSearchPipelineResult.SearchStatus.UNKNOWN_ERROR
                },
                error = error,
                provider = provider,
                summary = "Search failed: ${error.message}"
            )
            return Result.success(failureResult)
        }

        val searchResponse = searchResult.getOrThrow()
        val urls = searchResponse.results.take(maxResults)

        if (urls.isEmpty()) {
            Log.w(TAG, "Search returned 0 results for query '$query'")
            val noResultsResult = EnhancedWebSearchPipelineResult(
                query = query,
                results = emptyList(),
                totalResults = 0,
                searchTimeMs = System.currentTimeMillis() - startTime,
                status = EnhancedWebSearchPipelineResult.SearchStatus.NO_RESULTS,
                provider = provider,
                summary = "No results found for query: \"$query\""
            )
            return Result.success(noResultsResult)
        } else {
            Log.d(TAG, "[$provider] returned ${urls.size} results, scraping...")
        }

        val scrapingStartTime = System.currentTimeMillis()

        // Step 2: Scrape top results in parallel with timeout
        val scrapedResults = coroutineScope {
            urls.mapIndexed { index, result ->
                async {
                    val sid = generateSourceId(query, result.url, index)
                    try {
                        val scraped = withTimeoutOrNull(SCRAPE_TIMEOUT_MS) {
                            scrapingService.scrape(result.url, maxLength = MAX_SCRAPE_CHARS + 500)
                        }
                        
                        if (scraped != null && scraped.isSuccess) {
                            val content = scraped.getOrNull()?.content?.take(MAX_SCRAPE_CHARS) ?: ""
                            EnhancedWebSearchResult(
                                title = result.title,
                                url = result.url,
                                snippet = result.snippet,
                                content = content,
                                sourceType = if (content.isNotEmpty()) 
                                    EnhancedWebSearchResult.SourceType.FULLY_SCRAPED 
                                else 
                                    EnhancedWebSearchResult.SourceType.SNIPPET,
                                scraped = content.isNotEmpty(),
                                scrapeLengthChars = content.length,
                                metadata = EnhancedWebSearchResult.ResultMetadata(
                                    domain = extractDomain(result.url)
                                ),
                                sid = sid,
                                index = index
                            )
                        } else {
                            EnhancedWebSearchResult(
                                title = result.title,
                                url = result.url,
                                snippet = result.snippet,
                                content = "",
                                sourceType = EnhancedWebSearchResult.SourceType.SNIPPET,
                                scraped = false,
                                metadata = EnhancedWebSearchResult.ResultMetadata(
                                    domain = extractDomain(result.url)
                                ),
                                sid = sid,
                                index = index
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Scrape failed for ${result.url}: ${e.message}")
                        EnhancedWebSearchResult(
                            title = result.title,
                            url = result.url,
                            snippet = result.snippet,
                            content = "",
                            sourceType = EnhancedWebSearchResult.SourceType.SNIPPET,
                            scraped = false,
                            metadata = EnhancedWebSearchResult.ResultMetadata(
                                domain = extractDomain(result.url)
                            ),
                            sid = sid,
                            index = index
                        )
                    }
                }
            }.awaitAll()
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        val scrapingElapsed = System.currentTimeMillis() - scrapingStartTime
        val successfulScrapes = scrapedResults.count { it.scraped }
        val failedScrapes = scrapedResults.size - successfulScrapes

        Log.d(TAG, "Pipeline complete in ${totalElapsed}ms (scraping: ${scrapingElapsed}ms): ${scrapedResults.size} results, $successfulScrapes scraped")

        val result = EnhancedWebSearchPipelineResult(
            query = query,
            results = scrapedResults,
            totalResults = scrapedResults.size,
            searchTimeMs = System.currentTimeMillis() - startTime - scrapingElapsed,
            scrapingTimeMs = scrapingElapsed,
            status = if (successfulScrapes > 0) 
                EnhancedWebSearchPipelineResult.SearchStatus.SUCCESS 
            else 
                EnhancedWebSearchPipelineResult.SearchStatus.PARTIAL_SUCCESS,
            successfulScrapes = successfulScrapes,
            failedScrapes = failedScrapes,
            provider = provider,
            summary = "" // Will be set below
        )
        
        // Generate summary
        val summarizedResult = result.copy(summary = result.generateSummary())
        return Result.success(summarizedResult)
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URL(url).host?.removePrefix("www.") ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun generateSourceId(query: String, url: String, index: Int): String {
        // Generate a stable source ID for tracking and caching
        // Format: query_hash-domain-index
        val queryHash = url.hashCode().toString(16).padStart(8, '0')
        val domain = try {
            java.net.URL(url).host?.removePrefix("www.")?.substringBefore(".") ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        return "sid_${queryHash}_${domain}_$index"
    }

    // ── UI ──

    @Composable
    override fun ToolCallUI() {
        // No standalone UI — results shown via CacheToolUI
    }

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        if (data.has("query") && data.has("results")) {
            PipelineResultUI(data)
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
    private fun PipelineResultUI(data: JSONObject) {
        val query = data.optString("query", "")
        val resultsArray = data.optJSONArray("results")
        val totalResults = data.optInt("totalResults", 0)
        val searchTimeMs = data.optLong("searchTimeMs", 0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Header ──
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

            // ── Results ──
            if (resultsArray != null && resultsArray.length() > 0) {
                for (i in 0 until resultsArray.length()) {
                    val result = resultsArray.getJSONObject(i)
                    SearchResultCard(
                        title = result.optString("title", ""),
                        snippet = result.optString("snippet", ""),
                        url = result.optString("url", ""),
                        content = result.optString("content", ""),
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
    private fun SearchResultCard(
        title: String,
        snippet: String,
        url: String,
        content: String,
        position: Int
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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

                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Scraped content preview
                if (content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Text(
                            text = content.take(300) + if (content.length > 300) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(6.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
