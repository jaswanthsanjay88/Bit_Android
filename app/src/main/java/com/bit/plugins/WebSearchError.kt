package com.bit.plugins

import android.util.Log

sealed class WebSearchError : Exception() {
    data class RateLimitedException(val retryAfterSeconds: Int? = null, override val message: String = "Rate limited by search provider") : WebSearchError()
    data class BlockedException(override val message: String = "Search provider blocked this request (CAPTCHA/unusual traffic)") : WebSearchError()
    data class NoResultsException(override val message: String = "No results found for this query") : WebSearchError()
    data class NetworkException(override val message: String = "Network error") : WebSearchError()
    data class TimeoutException(override val message: String = "Search request timed out") : WebSearchError()
    data class InvalidQueryException(override val message: String = "Invalid search query") : WebSearchError()
    data class UnknownException(override val message: String = "Unknown error") : WebSearchError()

    companion object {
        fun fromException(e: Exception): WebSearchError {
            return when (e) {
                is RateLimitException -> RateLimitedException(message = e.message ?: "Rate limited")
                is BlockedException -> BlockedException(message = e.message ?: "Blocked")
                is java.net.SocketTimeoutException -> TimeoutException()
                is java.io.IOException -> NetworkException(message = e.message ?: "Network error")
                is IllegalArgumentException -> InvalidQueryException(message = e.message ?: "Invalid query")
                else -> UnknownException(message = e.message ?: "Unknown error")
            }
        }
    }
}

class RateLimitException(message: String) : Exception(message)
class BlockedException(message: String) : Exception(message)
