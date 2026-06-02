package com.bit.plugins

import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.models.messages.MessageContent
import com.bit.models.messages.ContentType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enhanced web search result with metadata about scraping success, source type, etc.
 */
data class EnhancedWebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val content: String,
    val sourceType: SourceType = SourceType.SNIPPET, // Snippet only vs scraped content
    val scraped: Boolean = false, // Was content successfully scraped
    val scrapeLengthChars: Int = 0,
    val metadata: ResultMetadata = ResultMetadata(),
    val sid: String = "", // Source ID for tracking/indexing
    val index: Int = -1 // Result index in search results
) {
    enum class SourceType {
        SNIPPET,        // Only title/snippet from search results
        PARTIALLY_SCRAPED, // Some content extracted
        FULLY_SCRAPED   // Full page content available
    }

    data class ResultMetadata(
        val domain: String = "",
        val favicon: String? = null,
        val language: String = "en",
        val contentType: String = "text/html"
    )

    fun toJSON(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("snippet", snippet)
        put("content", content.take(800)) // Limit for display
        put("sourceType", sourceType.name)
        put("scraped", scraped)
        put("domain", metadata.domain)
        put("sid", sid)
        put("index", index)
    }
}

/**
 * Enhanced pipeline result with detailed status and timing
 */
data class EnhancedWebSearchPipelineResult(
    val query: String,
    val results: List<EnhancedWebSearchResult>,
    val totalResults: Int,
    val searchTimeMs: Long,
    val scrapingTimeMs: Long = 0,
    val status: SearchStatus = SearchStatus.SUCCESS,
    val error: WebSearchError? = null,
    val successfulScrapes: Int = 0,
    val failedScrapes: Int = 0,
    val provider: String = "google", // Which provider succeeded
    val summary: String = "" // Condensed summary of all results
) {
    enum class SearchStatus {
        SUCCESS,
        PARTIAL_SUCCESS, // Retrieved results but scraping failed for some
        NO_RESULTS,
        RATE_LIMITED,
        BLOCKED,
        NETWORK_ERROR,
        TIMEOUT,
        INVALID_QUERY,
        UNKNOWN_ERROR
    }

    fun toJSON(): JSONObject = JSONObject().apply {
        put("query", query)
        put("status", status.name)
        put("totalResults", totalResults)
        put("searchTimeMs", searchTimeMs)
        put("scrapingTimeMs", scrapingTimeMs)
        put("successfulScrapes", successfulScrapes)
        put("failedScrapes", failedScrapes)
        put("provider", provider)
        put("summary", summary)
        
        val resultsArray = JSONArray()
        results.forEach { r -> resultsArray.put(r.toJSON()) }
        put("results", resultsArray)
        
        error?.let {
            put("error", it.message)
        }
    }

    fun generateSummary(): String {
        if (results.isEmpty()) {
            return "No results found for query: $query"
        }

        return buildString {
            append("Search Results for \"$query\": ")
            append("Found $totalResults results. ")

            // Add key findings from top results
            val keyFindings = mutableListOf<String>()
            results.take(3).forEach { result ->
                val snippet = result.snippet.take(100).replace("\n", " ")
                if (snippet.isNotBlank()) {
                    keyFindings.add("${result.title}: $snippet")
                }
            }

            if (keyFindings.isNotEmpty()) {
                append(keyFindings.joinToString(" | "))
            }

            // Add scraping status if relevant
            if (successfulScrapes > 0) {
                append(" (Retrieved full content from $successfulScrapes sources)")
            }
        }
    }

    fun toMessage(): Messages {
        val contentText = buildString {
            appendLine("**Web Search Results for: \"$query\"**")
            appendLine("Status: ${status.name} | Found: $totalResults results")
            appendLine("---")
            
            if (results.isEmpty()) {
                appendLine("No results found.")
            } else {
                results.forEachIndexed { idx, result ->
                    appendLine("**${idx + 1}. ${result.title}**")
                    appendLine("*${result.metadata.domain}*")
                    appendLine(result.snippet)
                    if (result.scraped) {
                        appendLine("📄 Content: ${result.content.take(150)}...")
                    } else {
                        appendLine("⚠️ Snippet only (scraping failed)")
                    }
                    appendLine()
                }
            }
            
            appendLine("---")
            appendLine("Search completed in ${searchTimeMs}ms")
            if (error != null) {
                appendLine("⚠️ Error: ${error.message}")
            }
        }

        return Messages(
            msgId = "search-${System.currentTimeMillis()}",
            role = Role.Assistant,
            content = MessageContent(
                contentType = ContentType.Text,
                content = contentText
            )
        )
    }
}
