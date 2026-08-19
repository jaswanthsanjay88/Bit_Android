package com.bit.network

import android.util.Log
import com.bit.models.plugins.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resilient Bing Search Client with automatic RSS fallback.
 * Ported from LastChat's battle-tested search architecture.
 *
 * Tier 1: Bing HTML Web Scraping (.b_algo elements)
 * Tier 2: Bing RSS XML Feed (https://www.bing.com/search?q=...&format=rss)
 *         - Bing RSS XML never triggers CAPTCHAs or rate-limiting on residential/mobile IPs.
 */
class BingSearchFallbackClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "BingSearchFallback"
        private const val BING_SEARCH_URL = "https://www.bing.com/search?q="
        private val ITEM_REGEX = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val XML_TAG_REGEX = Regex("<([a-zA-Z0-9_-]+)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    suspend fun search(
        query: String,
        maxResults: Int = 5,
        acceptLanguage: String = "en-US,en;q=0.9"
    ): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        val sanitized = query.trim().replace(Regex("\\s+"), " ")
        if (sanitized.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        val encodedQuery = URLEncoder.encode(sanitized, "UTF-8")
        val url = "$BING_SEARCH_URL$encodedQuery"

        // 1. Attempt standard Bing HTML Search
        try {
            val htmlResults = searchHtml(url, acceptLanguage, maxResults)
            if (htmlResults.isNotEmpty()) {
                Log.i(TAG, "Bing HTML returned ${htmlResults.size} results for: $sanitized")
                return@withContext Result.success(htmlResults)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bing HTML search failed: ${e.message}, falling back to Bing RSS XML")
        }

        // 2. Fallback to Bing RSS XML Search (unblockable endpoint)
        try {
            val rssUrl = if (url.contains("?")) "$url&format=rss" else "$url?format=rss"
            val rssResults = searchRss(rssUrl, acceptLanguage, maxResults)
            if (rssResults.isNotEmpty()) {
                Log.i(TAG, "Bing RSS returned ${rssResults.size} results for: $sanitized")
                return@withContext Result.success(rssResults)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bing RSS search failed: ${e.message}", e)
            return@withContext Result.failure(e)
        }

        Result.success(emptyList())
    }

    private fun searchHtml(url: String, acceptLanguage: String, maxResults: Int): List<SearchResult> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", acceptLanguage)
            .header("Referer", "https://www.bing.com/")
            .header("Cookie", "SRCHHPGUSR=ULSR=1")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val html = response.body.string()
        response.close()

        val doc = Jsoup.parse(html, url)
        val results = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        // Search for .b_algo elements
        val elements = doc.select("li.b_algo, div.b_algo")
        for (element in elements) {
            if (results.size >= maxResults) break

            val titleElement = element.selectFirst("h2 a, a[href]") ?: continue
            val link = titleElement.attr("href")
            val title = titleElement.text().trim()

            if (link.isBlank() || !link.startsWith("http") || title.isBlank() || link in seenUrls) continue
            seenUrls.add(link)

            val snippetElement = element.selectFirst(".b_caption p, .b_snippet, .b_lineclamp2, .b_lineclamp3, .b_lineclamp4, p")
            val snippet = snippetElement?.text()?.trim().orEmpty()

            results.add(
                SearchResult(
                    title = title,
                    snippet = snippet,
                    url = link,
                    position = results.size + 1
                )
            )
        }

        return results
    }

    private fun searchRss(rssUrl: String, acceptLanguage: String, maxResults: Int): List<SearchResult> {
        val request = Request.Builder()
            .url(rssUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "application/rss+xml,application/xml;q=0.9,text/xml;q=0.8,*/*;q=0.7")
            .header("Accept-Language", acceptLanguage)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }

        val xml = response.body.string()
        response.close()

        val results = mutableListOf<SearchResult>()
        for (match in ITEM_REGEX.findAll(xml)) {
            if (results.size >= maxResults) break
            val itemXml = match.groupValues[1]

            val title = extractXmlTag(itemXml, "title").cleanHtmlText()
            val link = extractXmlTag(itemXml, "link").cleanHtmlText()
            val description = extractXmlTag(itemXml, "description").cleanHtmlText()

            if (title.isNotBlank() && link.isNotBlank() && link.startsWith("http")) {
                results.add(
                    SearchResult(
                        title = title,
                        snippet = description,
                        url = link,
                        position = results.size + 1
                    )
                )
            }
        }

        return results
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val regex = Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun String.cleanHtmlText(): String {
        return Jsoup.parse(this).text().trim()
    }
}
