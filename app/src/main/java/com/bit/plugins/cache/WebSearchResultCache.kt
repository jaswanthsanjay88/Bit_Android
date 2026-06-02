package com.bit.plugins.cache

import android.util.Log
import com.bit.plugins.EnhancedWebSearchPipelineResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Simple in-memory cache for web search results with TTL support.
 * Prevents duplicate searches within a time window.
 */
class WebSearchResultCache {
    
    companion object {
        private const val TAG = "WebSearchCache"
        private val DEFAULT_TTL = TimeUnit.HOURS.toMillis(1) // 1 hour TTL
    }
    
    private data class CachedResult(
        val result: EnhancedWebSearchPipelineResult,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(ttlMs: Long = DEFAULT_TTL): Boolean {
            return System.currentTimeMillis() - timestamp > ttlMs
        }
    }
    
    private val cache = ConcurrentHashMap<String, CachedResult>()
    
    fun get(query: String): EnhancedWebSearchPipelineResult? {
        val cached = cache[query.lowercase()] ?: return null
        
        return if (cached.isExpired()) {
            cache.remove(query.lowercase())
            Log.d(TAG, "Cache entry expired for: $query")
            null
        } else {
            Log.d(TAG, "Cache hit for: $query")
            cached.result
        }
    }
    
    fun put(query: String, result: EnhancedWebSearchPipelineResult) {
        cache[query.lowercase()] = CachedResult(result)
        Log.d(TAG, "Cached result for: $query")
    }
    
    fun clear() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }
    
    fun size(): Int = cache.size
}
