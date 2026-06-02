package com.bit.plugins.services

import android.util.Log
import com.bit.models.plugins.DuckDuckGoSearchResponse
import com.bit.models.plugins.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WebScrapingSearchService {

    companion object {
        private const val TAG = "WebScrapingSearch"
        private const val DDG_HTML_SEARCH_URL = "https://html.duckduckgo.com/html/?q="
        private const val DDG_LITE_SEARCH_URL = "https://lite.duckduckgo.com/lite/?q="

        private const val MAX_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MIN_QUERY_LENGTH = 1
        private const val MAX_QUERY_LENGTH = 500
        private const val JSOUP_TIMEOUT_MS = 15_000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1"
    )

    suspend fun search(
        query: String,
        maxResults: Int = 5,
        safeSearch: Boolean = true,
        @Suppress("UNUSED_PARAMETER") region: String? = null,
        @Suppress("UNUSED_PARAMETER") timeRange: String? = null
    ): Result<DuckDuckGoSearchResponse> = withContext(Dispatchers.IO) {
        val sanitized = sanitizeQuery(query)

        if (sanitized.length < MIN_QUERY_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("Query too short (min $MIN_QUERY_LENGTH chars)"))
        }
        if (sanitized.length > MAX_QUERY_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("Query too long (max $MAX_QUERY_LENGTH chars)"))
        }

        val capped = maxResults.coerceIn(1, 10)
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val htmlResult = scrapeDuckDuckGoFromEndpoint(
                    query = sanitized,
                    maxResults = capped,
                    safeSearch = safeSearch,
                    baseUrl = DDG_HTML_SEARCH_URL,
                    endpointTag = "ddg_html"
                )
                if (htmlResult.results.isNotEmpty()) {
                    return@withContext Result.success(htmlResult)
                }

                val liteResult = scrapeDuckDuckGoFromEndpoint(
                    query = sanitized,
                    maxResults = capped,
                    safeSearch = safeSearch,
                    baseUrl = DDG_LITE_SEARCH_URL,
                    endpointTag = "ddg_lite"
                )
                if (liteResult.results.isNotEmpty()) {
                    return@withContext Result.success(liteResult)
                }

                val encoded = URLEncoder.encode(sanitized, "UTF-8")
                val googleNewsRss = scrapeNewsRss(
                    query = sanitized,
                    maxResults = capped,
                    rssUrl = "https://news.google.com/rss/search?q=$encoded&hl=en-IN&gl=IN&ceid=IN:en",
                    provider = "google_news_rss"
                )
                if (googleNewsRss.results.isNotEmpty()) {
                    return@withContext Result.success(googleNewsRss)
                }

                val bingNewsRss = scrapeNewsRss(
                    query = sanitized,
                    maxResults = capped,
                    rssUrl = "https://www.bing.com/news/search?q=$encoded&format=RSS",
                    provider = "bing_news_rss"
                )
                return@withContext Result.success(bingNewsRss)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < MAX_RETRIES - 1) {
                    val backoff = INITIAL_RETRY_DELAY_MS * (1 shl attempt) + Random.nextLong(0, 500)
                    delay(backoff)
                }
            }
        }

        Result.failure(lastException ?: IOException("DuckDuckGo search failed for: $sanitized"))
    }

    private suspend fun scrapeDuckDuckGoFromEndpoint(
        query: String,
        maxResults: Int,
        safeSearch: Boolean,
        baseUrl: String,
        endpointTag: String
    ): DuckDuckGoSearchResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        var searchUrl = baseUrl + encodedQuery
        if (safeSearch) searchUrl += "&kp=1"

        val profile = CurlImpersonateHelper.getRandomProfile()
        val impersonateClient = CurlImpersonateHelper.getClient(profile.type, timeoutSeconds = 15)

        val requestBuilder = Request.Builder().url(searchUrl)
        CurlImpersonateHelper.applyProfileHeaders(requestBuilder, searchUrl, profile)
        val request = requestBuilder.build()

        val html = impersonateClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DuckDuckGo HTTP ${response.code}: ${response.message}")
            }
            response.body.string()
        }

        val doc = Jsoup.parse(html, searchUrl)
        val results = parseDuckDuckGoResults(doc, maxResults)
        val elapsed = System.currentTimeMillis() - startTime

        DuckDuckGoSearchResponse(
            query = query,
            results = results,
            totalResults = results.size,
            searchTime = elapsed,
            provider = endpointTag
        )
    }

    private suspend fun scrapeNewsRss(
        query: String,
        maxResults: Int,
        rssUrl: String,
        provider: String
    ): DuckDuckGoSearchResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val profile = CurlImpersonateHelper.getRandomProfile()
        val impersonateClient = CurlImpersonateHelper.getClient(profile.type, timeoutSeconds = 15)

        val requestBuilder = Request.Builder().url(rssUrl)
        CurlImpersonateHelper.applyProfileHeaders(requestBuilder, rssUrl, profile)
        requestBuilder.header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
        val request = requestBuilder.build()

        impersonateClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RSS HTTP ${response.code}: ${response.message}")

            val xml = response.body.string()
            val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
            val items = doc.select("item")

            val results = mutableListOf<SearchResult>()
            for (item in items) {
                if (results.size >= maxResults) break

                val title = item.selectFirst("title")?.text()?.trim().orEmpty()
                val link = item.selectFirst("link")?.text()?.trim().orEmpty()
                val description = item.selectFirst("description")?.text()?.trim().orEmpty()

                if (title.length < 3 || link.isBlank()) continue

                results.add(
                    SearchResult(
                        title = title,
                        snippet = description.take(260),
                        url = link,
                        position = results.size + 1
                    )
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            DuckDuckGoSearchResponse(
                query = query,
                results = results,
                totalResults = results.size,
                searchTime = elapsed,
                provider = provider
            )
        }
    }


    private fun parseDuckDuckGoResults(doc: Document, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        try {
            var resultElements = doc.select(".result")
            if (resultElements.isEmpty()) resultElements = doc.select(".links_main")
            if (resultElements.isEmpty()) resultElements = doc.select(".result__body, tr")

            for (element in resultElements) {
                if (results.size >= maxResults) break

                if (element.hasClass("result--ad") || element.select(".badge--ad").isNotEmpty()) continue

                val titleElement = element.selectFirst(".result__title, .result__a, a.result-link, a.result__a")
                val title = titleElement?.text()?.trim().orEmpty()
                if (title.length < 3) continue

                val url = extractDDGUrl(element) ?: continue
                if (url in seenUrls) continue
                seenUrls.add(url)

                val snippet = element.selectFirst(".result__snippet, .result__desc, .snippet, .result-snippet, td.result-snippet")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                results.add(
                    SearchResult(
                        title = title,
                        snippet = snippet,
                        url = url,
                        position = results.size + 1
                    )
                )
            }

            if (results.isEmpty()) {
                val links = doc.select("a[href*='uddg='], a[href^='/l/?'], a.result__a[href], a.result-link[href]")
                for (link in links) {
                    if (results.size >= maxResults) break

                    val rawHref = link.attr("href")
                    val url = decodeDDGUrl(rawHref)
                    if (!isLikelySearchResultUrl(url) || url in seenUrls) continue

                    val title = link.text().trim()
                    if (title.length < 3) continue

                    seenUrls.add(url)
                    val parentText = link.parent()?.text()?.trim().orEmpty()
                    val snippet = parentText.removePrefix(title).trim().take(220)

                    results.add(
                        SearchResult(
                            title = title,
                            snippet = snippet,
                            url = url,
                            position = results.size + 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing DDG HTML: ${e.message}")
        }

        return results
    }

    private fun extractDDGUrl(element: Element): String? {
        return try {
            val linkElement = element.selectFirst(".result__url, .result__extras__url, a.result-link, a.result__a, a[href]")
            if (linkElement != null) {
                val href = linkElement.attr("href")
                if (href.isNotEmpty()) {
                    val decoded = decodeDDGUrl(href)
                    if (decoded.isNotEmpty() && decoded.startsWith("http")) return decoded
                }

                val textUrl = linkElement.text().trim()
                if (textUrl.startsWith("http")) return textUrl
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting URL: ${e.message}")
            null
        }
    }

    private fun decodeDDGUrl(url: String): String {
        return try {
            when {
                url.contains("uddg=") -> {
                    val start = url.indexOf("uddg=") + 5
                    val end = url.indexOf("&", start).let { if (it == -1) url.length else it }
                    URLDecoder.decode(url.substring(start, end), "UTF-8")
                }
                url.startsWith("//") -> "https:$url"
                url.startsWith("http") -> url
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding URL: ${e.message}")
            url
        }
    }

    private fun isLikelySearchResultUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        return !lower.contains("duckduckgo.com/") &&
            !lower.contains("/y.js") &&
            !lower.contains("/about") &&
            !lower.contains("/settings")
    }

    private fun sanitizeQuery(query: String): String {
        return query
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
    }
}
