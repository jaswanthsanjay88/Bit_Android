package com.bit.plugins.services

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * Utility to construct OkHttpClient instances that impersonate modern browsers
 * (Chrome/Firefox) by matching their exact TLS cipher suites, TLS versions,
 * ALPN protocols, and HTTP headers, similar to curl-impersonate.
 */
object CurlImpersonateHelper {

    enum class BrowserType {
        CHROME,
        FIREFOX
    }

    data class BrowserProfile(
        val type: BrowserType,
        val userAgent: String,
        val headers: Map<String, String>,
        val cipherSuites: List<CipherSuite>
    )

    // Chrome 120 TLS Ciphers Order (Standard Chrome fingerprint)
    private val CHROME_CIPHER_SUITES = listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384
    )

    // Firefox 121 TLS Ciphers Order (Standard Firefox fingerprint)
    private val FIREFOX_CIPHER_SUITES = listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384
    )

    val PROFILES = listOf(
        BrowserProfile(
            type = BrowserType.CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "gzip, deflate, br",
                "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            ),
            cipherSuites = CHROME_CIPHER_SUITES
        ),
        BrowserProfile(
            type = BrowserType.FIREFOX,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept-Encoding" to "gzip, deflate, br",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            ),
            cipherSuites = FIREFOX_CIPHER_SUITES
        )
    )

    // Cached clients with different timeouts to reuse connections and SSL sessions efficiently
    private val clientPool15s = PROFILES.associate { it.type to buildClient(it, 15) }
    private val clientPool30s = PROFILES.associate { it.type to buildClient(it, 30) }

    fun getRandomProfile(): BrowserProfile {
        return PROFILES.random()
    }

    /**
     * Gets a pre-configured OkHttpClient matching the browser profile
     */
    fun getClient(type: BrowserType, timeoutSeconds: Long = 15): OkHttpClient {
        return if (timeoutSeconds <= 15) {
            clientPool15s[type] ?: clientPool15s.values.first()
        } else {
            clientPool30s[type] ?: clientPool30s.values.first()
        }
    }

    /**
     * Builds an OkHttpClient configured to mimic the provided browser profile's TLS signature
     */
    private fun buildClient(profile: BrowserProfile, timeoutSeconds: Long): OkHttpClient {
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(*profile.cipherSuites.toTypedArray())
            .build()

        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
    }

    /**
     * Decorates an existing OkHttp Request.Builder with the browser profile's headers in standard order
     */
    fun applyProfileHeaders(builder: Request.Builder, url: String, profile: BrowserProfile) {
        builder.header("User-Agent", profile.userAgent)
        profile.headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        if (!url.contains("duckduckgo.com")) {
            builder.header("Referer", "https://duckduckgo.com/")
        }
    }
}
