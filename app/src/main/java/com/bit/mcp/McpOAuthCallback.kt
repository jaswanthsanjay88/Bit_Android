package com.bit.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Result dispatched when an OAuth callback intent is captured.
 */
sealed interface McpOAuthResult {
    data class Success(val state: String, val code: String) : McpOAuthResult
    data class Error(val state: String?, val error: String, val errorDescription: String?) : McpOAuthResult
}

/**
 * Shared hub for distributing OAuth callback results from Activity to Coordinator.
 */
object McpOAuthCallbackHub {
    private val _results = MutableSharedFlow<McpOAuthResult>(extraBufferCapacity = 16)
    val results: SharedFlow<McpOAuthResult> = _results.asSharedFlow()

    fun emit(result: McpOAuthResult) {
        _results.tryEmit(result)
    }
}
