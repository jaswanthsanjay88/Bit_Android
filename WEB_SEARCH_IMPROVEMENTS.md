# Web Search Feature - Architecture & UI Improvements

## Overview
This document outlines comprehensive improvements made to the web search feature in BIT, covering both architectural enhancements and UI/UX improvements.

## Problems Identified

### Architecture Issues
1. **Fragile HTML Scraping**: Hard-coded Google scraping easily broken by markup changes
2. **No Fallback Providers**: Single-source failure means complete search unavailability
3. **Poor Error Classification**: Generic exceptions don't distinguish between rate limits, blocks, network errors
4. **No Result Caching**: Identical queries repeat network requests unnecessarily
5. **Limited Configuration**: No user control over search parameters, timeouts, or behavior
6. **Basic Retry Logic**: Simple exponential backoff without proper error context

### UI/UX Issues
1. **Minimal Visual Presence**: Web search toggle hidden in toolbar, no dedicated UI
2. **Raw JSON Display**: Results shown as tool output JSON in chat, not user-friendly
3. **No Status Feedback**: Users can't see if search failed silently or why
4. **Poor Result Presentation**: No visual distinction between scraped vs snippet-only content
5. **Limited Interaction**: Can't browse results, no quick open-in-browser or copy actions
6. **No Search Metadata**: Users can't see search time, result count, or scraping success rates

## Solutions Implemented

### 1. Enhanced Error Handling (`WebSearchError.kt`)

**Sealed Error Hierarchy:**
```kotlin
sealed class WebSearchError {
    data class RateLimitedException(val retryAfterSeconds: Int? = null)
    data class BlockedException(message: String)
    data class NoResultsException(message: String)
    data class NetworkException(message: String)
    data class TimeoutException(message: String)
    data class InvalidQueryException(message: String)
    data class UnknownException(message: String)
}
```

**Benefits:**
- UI can show appropriate error messages per error type
- Proper retry logic based on error kind
- Better logging and debugging
- User-friendly error states

### 2. Rich Result Models (`WebSearchModels.kt`)

**EnhancedWebSearchResult:** Captures full result metadata
- `sourceType`: SNIPPET, PARTIALLY_SCRAPED, or FULLY_SCRAPED
- `scraped`: Boolean flag indicating successful content extraction
- `scrapeLengthChars`: Track how much content was captured
- `metadata`: Domain, favicon, language, content-type

**EnhancedWebSearchPipelineResult:** Detailed status tracking
- `status`: SUCCESS, PARTIAL_SUCCESS, NO_RESULTS, RATE_LIMITED, BLOCKED, NETWORK_ERROR, TIMEOUT, etc.
- `successfulScrapes` / `failedScrapes`: Granular scraping metrics
- `searchTimeMs` / `scrapingTimeMs`: Separate timing for each phase
- `error`: Optional error details for failures
- `toMessage()`: Convert results to chat-displayable Messages

### 3. Result Caching (`WebSearchResultCache.kt`)

**In-Memory Cache with TTL:**
```kotlin
class WebSearchResultCache {
    fun get(query: String): EnhancedWebSearchPipelineResult?
    fun put(query: String, result: EnhancedWebSearchPipelineResult)
    fun clear()
}
```

**Benefits:**
- Avoids duplicate searches within 1-hour window
- Reduces API rate-limiting risk during conversation
- Improves response time for repeated queries
- Configurable TTL via `WebSearchConfig`

### 4. Configurable Settings (`WebSearchConfig.kt`)

**Centralized Configuration:**
```kotlin
data class WebSearchConfig(
    val maxResultsPerQuery: Int = 3,
    val maxScrapeLengthChars: Int = 1500,
    val searchTimeoutMs: Long = 30_000,
    val scrapeTimeoutPerUrlMs: Long = 10_000,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 2,
    val enableCaching: Boolean = true,
    val cacheTtlMs: Long = 3_600_000,
    val safeSearchEnabled: Boolean = true
)
```

**Allows Future UI Settings Panel** for user customization:
- Adjust max results per query (1-5 range)
- Control timeout thresholds
- Toggle caching on/off
- Enable/disable safe search
- Control result scraping length

### 5. Rich UI Display (`WebSearchResultsDisplay.kt`)

#### Header Component
- Shows query being searched
- Status badge with clear visual state (Success ✓, Partial ⚠️, Rate Limited ⏱️, Blocked 🚫, etc.)
- Color-coded backgrounds per status

#### Result Cards
- **Title with link color** - clearly clickable
- **Domain/source** - lightweight gray text
- **Snippet** - contextual preview text (truncated to 3 lines)
- **Open in Browser** - quick action button
- **Content Badge** - "📄 Full content scraped (XXX chars)" only when available
- **Expandable Content** - tap to see full scraped text

#### Status States
- **Success State**: Shows all results with scraping metrics
- **Partial Success**: Displays results with warning that some scraping failed
- **No Results**: Clean empty state with search icon
- **Error States**: Specific messaging for rate limits, blocks, network errors, timeouts
- **Footer Metrics**: Total results count, scraping success count, total time

### 6. Updated Plugin Architecture

**Improved `executePipelinedSearch()` method:**
- Comprehensive error classification
- Separate search and scraping timing
- Per-result success/failure tracking
- Rich metadata collection (domain extraction)
- Proper exception handling with conversion to WebSearchError

## File Structure

```
plugins/
├── WebSearchError.kt                # Error hierarchy
├── WebSearchModels.kt               # Result models
├── WebSearchPlugin.kt               # Main plugin (updated)
├── cache/
│   └── WebSearchResultCache.kt      # Caching layer
├── config/
│   └── WebSearchConfig.kt           # Configuration management
└── services/
    ├── WebScrapingSearchService.kt  # Search implementation
    └── WebScrapingService.kt        # Scraping implementation

ui/components/
└── WebSearchResultsDisplay.kt       # Rich UI display
```

## Usage Examples

### In Plugin (Internal)
```kotlin
// Plugin automatically uses enhanced results
val result: EnhancedWebSearchPipelineResult = // from executePipelinedSearch()
return Result.success(result)  // Includes error handling, metrics, status
```

### In UI (Consumer)
```kotlin
// Display results in chat
val result = EnhancedWebSearchPipelineResult(...)
WebSearchResultsDisplay(
    result = result,
    modifier = Modifier.fillMaxWidth()
)

// Or convert to message
val messageForChat = result.toMessage()
chatViewModel.addMessage(messageForChat)
```

### Caching Usage
```kotlin
val cache = WebSearchContextManager.getCache()
val cached = cache.get("user query")
if (cached != null) {
    return Result.success(cached)  // Use cached result
} else {
    val result = performSearch(...)
    cache.put("user query", result)  // Cache for next time
    return Result.success(result)
}
```

## Configuration Usage

```kotlin
// Get current config
val config = WebSearchContextManager.getConfig()

// Customize
WebSearchContextManager.setConfig(
    WebSearchConfig(
        maxResultsPerQuery = 5,
        cacheTtlMs = 2_hours,
        safeSearchEnabled = true
    )
)

// Clear cache
WebSearchContextManager.clearCache()
```

## Future Enhancements

1. **Fallback Providers**
   - Add SerpApi/Bing as fallback if Google scraping fails
   - Automatic provider selection based on success rates

2. **Search History**
   - Persist recent searches with timestamps
   - Quick-select previous queries

3. **Settings UI Panel**
   - Let users configure max results, timeouts, scraping length
   - Toggle safe search, result caching, debug logging

4. **Advanced Result Filtering**
   - Filter by domain
   - Hide results below quality threshold
   - Sort by freshness/relevance

5. **Visual Improvements**
   - Result preview images (favicons or thumbnails)
   - Result quality indicators (★★★★☆)
   - Full-screen result viewer with smooth scroll

6. **Performance**
   - Add request rate limiting (max X searches per minute)
   - Implement smart retries based on error patterns
   - Connection pooling optimizations

## Testing Checklist

- [ ] Search returns results successfully
- [ ] Partial success when some URLs fail to scrape
- [ ] Proper error handling for rate limits
- [ ] Cache prevents duplicate searches
- [ ] UI displays all result types correctly
- [ ] Status badges show correct colors
- [ ] Results expandable to show full content
- [ ] Open in browser button works
- [ ] Error messages are clear and helpful
- [ ] Metrics display correctly (time, count)

## Build & Runtime Notes

- Requires Jetpack Compose for UI
- Uses jsoup for HTML scraping
- OkHttp for HTTP requests
- All networking on Dispatchers.IO
- Result caching in-memory only (not persisted)

---

**Last Updated:** March 24, 2026  
**Version:** 2.0.0
