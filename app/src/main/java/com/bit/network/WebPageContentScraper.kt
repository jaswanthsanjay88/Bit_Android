package com.bit.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class ScrapedPageResult(
    val url: String,
    val title: String?,
    val description: String?,
    val content: String
)

/**
 * Universal Web Page Content Scraper.
 * Ported from LastChat's ShareIntentResolver and web scraping engine.
 *
 * Downloads live web pages and transforms raw HTML into clean, token-efficient Markdown
 * by stripping scripts/styles/SVGs and preserving semantic paragraph hierarchy.
 */
class WebPageContentScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "WebPageContentScraper"
        private const val MAX_CONTENT_CHARS = 12000
    }

    suspend fun scrape(url: String): Result<ScrapedPageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                url
            } else {
                "https://$url"
            }

            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                error("HTTP request failed with status code ${response.code}")
            }

            val html = response.body.string()
            response.close()

            if (html.isBlank()) {
                error("Received empty response body from $normalizedUrl")
            }

            val doc = Jsoup.parse(html, normalizedUrl)

            // 1. Extract Title
            val title = doc.title().takeIf { it.isNotBlank() } ?: doc.selectFirst("h1")?.text()?.trim()

            // 2. Extract Meta Description
            val description = doc.selectFirst("meta[name='description'], meta[property='og:description']")
                ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

            // 3. Clean and Extract Readable Markdown Text
            doc.select("script, style, noscript, svg, nav, footer, header, aside, .ad, .ads, .sidebar, .cookie-banner, .advertisement").remove()

            val textContent = doc.body()
                .html()
                .replace(Regex("""(?is)</?(h[1-6]|p|li|pre|blockquote|br|div|section|article|main)\b[^>]*>"""), "\n\n")
                .replace(Regex("""(?is)<[^>]+>"""), " ")
                .let { Jsoup.parse(it).text() }
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")

            val finalContent = if (textContent.length > MAX_CONTENT_CHARS) {
                textContent.take(MAX_CONTENT_CHARS) + "\n\n[Content truncated for length]"
            } else {
                textContent
            }

            ScrapedPageResult(
                url = normalizedUrl,
                title = title,
                description = description,
                content = finalContent
            )
        }
    }
}
