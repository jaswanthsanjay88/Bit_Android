package com.atharva.ollama.util;

import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web search helper using DuckDuckGo HTML scraping.
 * This is more reliable than API-based search which often gets rate-limited.
 */
public class WebSearchHelper {
    private static final String TAG = "WebSearchHelper";
    
    // DuckDuckGo HTML search URL (Lite version - easier to parse)
    private static final String DDG_SEARCH_URL = "https://html.duckduckgo.com/html/?q=";
    
    // User agent to avoid being blocked
    private static final String USER_AGENT = 
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Search result data class
     */
    public static class SearchResult {
        public final String title;
        public final String snippet;
        public final String url;
        public final int index;

        public SearchResult(int index, String title, String snippet, String url) {
            this.index = index;
            this.title = title;
            this.snippet = snippet;
            this.url = url;
        }
        
        @Override
        public String toString() {
            return String.format("[%d] %s\n%s\nURL: %s", index, title, snippet, url);
        }
    }

    /**
     * Callback interface for search results
     */
    public interface SearchCallback {
        void onSuccess(String formattedResults, List<SearchResult> results);
        void onError(String error);
    }

    /**
     * Perform an asynchronous web search using DuckDuckGo
     */
    public static void searchAsync(String query, int maxResults, SearchCallback callback) {
        searchAsync(query, maxResults, "", callback);
    }
    
    /**
     * Perform an asynchronous web search using DuckDuckGo with site filter
     * @param query The search query
     * @param maxResults Maximum results to return
     * @param siteFilter Site filter like "site:reddit.com" or empty for all
     * @param callback Callback for results
     */
    public static void searchAsync(String query, int maxResults, String siteFilter, SearchCallback callback) {
        executor.execute(() -> {
            try {
                // Prepend site filter to query
                String fullQuery = (siteFilter != null && !siteFilter.isEmpty()) 
                    ? query + " " + siteFilter 
                    : query;
                    
                String result = search(fullQuery, maxResults);
                List<SearchResult> results = parseResults(fullQuery, maxResults);
                callback.onSuccess(result, results);
            } catch (Exception e) {
                Log.e(TAG, "Search error", e);
                callback.onError(e.getMessage());
            }
        });
    }

    /**
     * Perform a synchronous web search using DuckDuckGo HTML scraping
     *
     * @param query      The search query
     * @param maxResults Maximum number of results to return
     * @return Formatted search results as a string for LLM context
     */
    public static String search(String query, int maxResults) throws IOException {
        List<SearchResult> results = parseResults(query, maxResults);
        
        if (results.isEmpty()) {
            return "No search results found for: " + query;
        }
        
        return formatResultsForLLM(results, query);
    }

    /**
     * Parse search results from DuckDuckGo HTML
     */
    private static List<SearchResult> parseResults(String query, int maxResults) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
        String searchUrl = DDG_SEARCH_URL + encodedQuery;
        
        Log.d(TAG, "Searching DuckDuckGo for: " + query);
        Log.d(TAG, "URL: " + searchUrl);
        
        Document doc = Jsoup.connect(searchUrl)
                .userAgent(USER_AGENT)
                .timeout(15000) // 15 second timeout
                .referrer("https://duckduckgo.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .get();
        
        // Select search result containers
        Elements resultElements = doc.select(".result");
        
        if (resultElements.isEmpty()) {
            // Try alternative selectors
            resultElements = doc.select(".links_main");
            if (resultElements.isEmpty()) {
                resultElements = doc.select(".result__body");
            }
        }
        
        Log.d(TAG, "Found " + resultElements.size() + " results");
        
        int count = 0;
        for (Element result : resultElements) {
            if (count >= maxResults) break;
            
            // Extract title
            String title = "";
            Element titleElement = result.select(".result__title, .result__a, a.result-link").first();
            if (titleElement != null) {
                title = titleElement.text().trim();
            }
            
            // Extract snippet/description
            String snippet = "";
            Element snippetElement = result.select(".result__snippet, .result__desc, .snippet").first();
            if (snippetElement != null) {
                snippet = snippetElement.text().trim();
            }
            
            // Extract URL
            String url = "";
            Element urlElement = result.select(".result__url, .result__extras__url, a.result-link").first();
            if (urlElement != null) {
                url = urlElement.attr("href");
                if (url.isEmpty()) {
                    url = urlElement.text().trim();
                }
                // Clean up DuckDuckGo redirect URLs
                if (url.contains("uddg=")) {
                    try {
                        int start = url.indexOf("uddg=") + 5;
                        int end = url.indexOf("&", start);
                        if (end == -1) end = url.length();
                        url = java.net.URLDecoder.decode(url.substring(start, end), "UTF-8");
                    } catch (Exception e) {
                        // Keep original URL
                    }
                }
            }
            
            // Skip if essential data is missing
            if (title.isEmpty() && snippet.isEmpty()) {
                continue;
            }
            
            // Skip ads/sponsored results
            if (result.hasClass("result--ad") || result.select(".badge--ad").size() > 0) {
                continue;
            }
            
            count++;
            results.add(new SearchResult(count, title, snippet, url));
        }
        
        return results;
    }

    /**
     * Format search results for LLM context
     */
    private static String formatResultsForLLM(List<SearchResult> results, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for: \"").append(query).append("\"\n\n");
        
        for (SearchResult result : results) {
            sb.append("--- Result [").append(result.index).append("] ---\n");
            sb.append("Title: ").append(result.title).append("\n");
            if (!result.snippet.isEmpty()) {
                sb.append("Content: ").append(result.snippet).append("\n");
            }
            if (!result.url.isEmpty()) {
                sb.append("Source: ").append(result.url).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Simple synchronous search that returns formatted string
     * For use in existing code that expects a simple string result
     */
    public static String quickSearch(String query) {
        try {
            return search(query, 5);
        } catch (Exception e) {
            Log.e(TAG, "Quick search failed", e);
            return "Web search failed: " + e.getMessage();
        }
    }
}
