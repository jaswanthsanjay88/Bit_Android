package com.bit.api.util

object ProviderRetryPolicy {
    const val MAX_ATTEMPTS = 6
    const val MAX_RETRIES = 5

    fun delayMillis(attempt: Int): Long = when {
        attempt <= 3 -> 5_000L
        else -> 30_000L
    }

    fun shouldRetryHttp(
        statusCode: Int,
        body: String?,
        retryableCodes: Set<Int> = setOf(429, 502, 503, 504),
    ): Boolean {
        if (statusCode in retryableCodes) return true
        if (statusCode == 200 && body != null && isFailedToGenerateOutcome(body)) return true
        return false
    }

    private fun isFailedToGenerateOutcome(body: String): Boolean =
        body.contains("failed to generate", ignoreCase = true)
}

fun Exception.asRetryableTransportError(): Exception? = when (this) {
    is java.net.SocketTimeoutException -> this
    is java.net.ConnectException -> this
    is javax.net.ssl.SSLException -> this
    is java.net.SocketException -> if (message?.contains("reset", ignoreCase = true) == true) this else null
    else -> null
}
