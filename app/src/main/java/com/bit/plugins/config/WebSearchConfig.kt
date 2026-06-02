package com.bit.plugins.config

import com.bit.plugins.cache.WebSearchResultCache

/**
 * Configuration for web search behavior and performance tuning
 */
data class WebSearchConfig(
    // Search parameters
    val maxResultsPerQuery: Int = 3,          // 1-5 results to scrape
    val maxScrapeLengthChars: Int = 1500,     // Max content per URL
    
    // Timeout controls
    val searchTimeoutMs: Long = 30_000,       // Overall search timeout
    val scrapeTimeoutPerUrlMs: Long = 10_000, // Per-URL scrape timeout
    
    // Rate limit handling
    val enableRetry: Boolean = true,
    val maxRetries: Int = 2,
    val initialRetryDelayMs: Long = 1500,
    
    // Caching
    val enableCaching: Boolean = true,
    val cacheTtlMs: Long = 3_600_000,         // 1 hour TTL
    
    // User-Agent rotation
    val enableUserAgentRotation: Boolean = true,
    
    // Safe search
    val safeSearchEnabled: Boolean = true,
    
    // Advanced
    val enableFallbackProviders: Boolean = true, // Future: fallback to APIs
    val debugLogging: Boolean = false
)

/**
 * Web Search runtime context (singleton-like, but configurable)
 */
object WebSearchContextManager {
    private var _config = WebSearchConfig()
    private val _cache = WebSearchResultCache()
    
    fun getConfig(): WebSearchConfig = _config
    
    fun setConfig(config: WebSearchConfig) {
        _config = config
    }
    
    fun getCache(): WebSearchResultCache = _cache
    
    fun clearCache() {
        _cache.clear()
    }
}
