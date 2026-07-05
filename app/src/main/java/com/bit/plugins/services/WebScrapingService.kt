package com.bit.plugins.services

import com.bit.models.plugins.ScrapedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.Charset
import kotlin.random.Random

/**
 * Web scraping service with readability-based content extraction.
 * Uses CurlImpersonateHelper for anti-detection, retry logic, and cookie persistence.
 */
class WebScrapingService {

    private val cookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies.toMutableList()
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client = CurlImpersonateHelper.getClient(CurlImpersonateHelper.BrowserType.CHROME, timeoutSeconds = 30)
        .newBuilder()
        .cookieJar(cookieJar)
        .build()

    companion object {
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY = 1000L

        val CONTENT_SELECTORS = listOf(
            "article", "main", "[role=main]", ".post-content", ".entry-content",
            ".article-content", ".content", "#content", ".post", ".article"
        )

        val NOISE_SELECTORS = listOf(
            "script", "style", "nav", "header", "footer", "aside", ".sidebar",
            ".ads", ".advertisement", ".social-share", ".comments", "#comments",
            ".cookie-banner", ".popup", ".modal", "iframe[src*=ads]"
        )
    }

    /**
     * Scrape a URL with retry logic and readability-based extraction.
     */
    suspend fun scrape(
        url: String,
        selector: String? = null,
        maxLength: Int = 5000,
        useReadability: Boolean = true,
        @Suppress("UNUSED_PARAMETER") extractStructuredData: Boolean = true
    ): Result<ScrapedContent> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                val startTime = System.currentTimeMillis()

                if (!url.lowercase().let { it.startsWith("http://") || it.startsWith("https://") }) {
                    return@withContext Result.failure(IllegalArgumentException("Invalid URL format: $url"))
                }

                val profile = CurlImpersonateHelper.getRandomProfile()
                val requestBuilder = Request.Builder().url(url)
                CurlImpersonateHelper.applyProfileHeaders(requestBuilder, url, profile)
                val request = requestBuilder.build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }

                    val contentType = response.header("Content-Type")
                    val charset = detectCharset(contentType)
                    val bodyBytes = response.body.bytes()
                    val html = String(bodyBytes, charset)
                    val fetchTime = System.currentTimeMillis() - startTime

                    val document: Document = Jsoup.parse(html, url)

                    val extractedContent = when {
                        !selector.isNullOrBlank() -> extractBySelector(document, selector)
                        useReadability -> extractWithReadability(document)
                        else -> extractMainContent(document)
                    }

                    val metadata = extractMetadata(document)
                    val sanitizedContent = sanitizeContent(extractedContent).take(maxLength)

                    val content = ScrapedContent(
                        url = url,
                        title = sanitizeContent(extractBestTitle(document)),
                        content = sanitizedContent,
                        contentLength = sanitizedContent.length,
                        fetchTime = fetchTime,
                        metadata = metadata
                    )

                    return@withContext Result.success(content)
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) {
                    delay(INITIAL_RETRY_DELAY * (1 shl attempt) + Random.nextLong(0, 500))
                }
            }
        }

        Result.failure(lastException ?: IOException("Scraping failed after $MAX_RETRIES attempts"))
    }

    // ── Private helpers ──

    private fun detectCharset(contentType: String?): Charset {
        return try {
            contentType?.let {
                Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)
                    ?.groupValues?.get(1)?.let { cs ->
                        try { Charset.forName(cs.trim('"')) } catch (_: Exception) { null }
                    }
            } ?: Charsets.UTF_8
        } catch (_: Exception) { Charsets.UTF_8 }
    }

    private fun sanitizeContent(content: String): String {
        return content
            .replace(Regex("[\\p{Cc}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractBestTitle(document: Document): String {
        return listOfNotNull(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.title()
        ).firstOrNull { it.isNotBlank() } ?: "Untitled"
    }

    private fun extractBySelector(document: Document, selector: String): String {
        return try {
            val elements = document.select(selector)
            if (elements.isEmpty()) "No elements found matching selector: $selector"
            else elements.joinToString("\n\n") { extractElementContent(it) }.trim()
        } catch (e: Exception) {
            "Error extracting content with selector '$selector': ${e.message}"
        }
    }

    private fun extractElementContent(element: Element): String {
        return buildString {
            element.select("h1, h2, h3, h4, h5, h6").forEach {
                val text = sanitizeContent(it.text())
                if (text.isNotBlank()) appendLine("## $text")
            }
            element.select("p").forEach {
                val text = sanitizeContent(it.text())
                if (text.length > 10) { appendLine(text); appendLine() }
            }
            element.select("ul, ol").forEach { list ->
                list.select("li").forEach {
                    val text = sanitizeContent(it.text())
                    if (text.isNotBlank()) appendLine("• $text")
                }
                appendLine()
            }
            element.select("table").forEach { table ->
                table.select("tr").forEach { row ->
                    appendLine(row.select("th, td").joinToString(" | ") { sanitizeContent(it.text()) })
                }
                appendLine()
            }
            element.select("pre, code").forEach {
                val text = sanitizeContent(it.text())
                if (text.isNotBlank()) { appendLine("```"); appendLine(text); appendLine("```"); appendLine() }
            }
            element.select("blockquote").forEach {
                val text = sanitizeContent(it.text())
                if (text.isNotBlank()) { appendLine("> $text"); appendLine() }
            }
        }.trim()
    }

    private fun extractMainContent(document: Document): String {
        val doc = document.clone()
        NOISE_SELECTORS.forEach { doc.select(it).remove() }
        val mainContent = CONTENT_SELECTORS.firstNotNullOfOrNull { doc.selectFirst(it) } ?: doc.body()

        return buildString {
            for (element in mainContent.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre")) {
                val text = sanitizeContent(element.text())
                if (text.length > 15) {
                    when (element.tagName()) {
                        in listOf("h1", "h2", "h3", "h4", "h5", "h6") -> appendLine("\n## $text")
                        "blockquote" -> appendLine("\n> $text")
                        "pre" -> appendLine("\n```\n$text\n```")
                        else -> { appendLine(text); appendLine() }
                    }
                }
            }
        }.trim()
    }

    private fun extractWithReadability(document: Document): String {
        val doc = document.clone()
        NOISE_SELECTORS.forEach { doc.select(it).remove() }

        val bestElement = doc.select("div, article, section, main")
            .map { element ->
                var score = element.text().length / 100
                score += element.select("p").size * 10
                val textLen = element.text().length
                val linkTextLen = element.select("a").sumOf { it.text().length }
                val linkDensity = if (textLen > 0) linkTextLen.toFloat() / textLen else 0f
                score -= (linkDensity * 50).toInt()
                if (element.classNames().any { it.contains("content") || it.contains("article") || it.contains("post") }) score += 25
                if (element.classNames().any { it.contains("comment") || it.contains("footer") || it.contains("sidebar") }) score -= 25
                element to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        return if (bestElement != null) extractElementContent(bestElement)
        else extractMainContent(document)
    }

    /** Extract only description and author — the only metadata consumed downstream. */
    private fun extractMetadata(document: Document): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        val desc = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: ""
        if (desc.isNotBlank()) metadata["description"] = sanitizeContent(desc)

        val author = document.selectFirst("meta[name=author]")?.attr("content")
            ?: document.selectFirst("meta[property=article:author]")?.attr("content")
            ?: ""
        if (author.isNotBlank()) metadata["author"] = sanitizeContent(author)

        return metadata
    }
}
