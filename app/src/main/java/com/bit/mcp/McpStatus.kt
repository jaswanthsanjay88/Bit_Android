package com.bit.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * Lifecycle status of an MCP server connection.
 */
@Serializable
sealed class McpStatus {
    @Serializable
    data object Idle : McpStatus()

    @Serializable
    data object Connecting : McpStatus()

    @Serializable
    data object Connected : McpStatus()

    @Serializable
    data class Reconnecting(val attempt: Int, val maxAttempts: Int = 5) : McpStatus()

    @Serializable
    data class NeedsAuthorization(val resource: String? = null) : McpStatus()

    @Serializable
    data object Authorizing : McpStatus()

    @Serializable
    data object Stopped : McpStatus()

    @Serializable
    data class Error(val message: String, val cause: String? = null) : McpStatus() {
        companion object {
            fun from(e: Throwable, fallbackMessage: String = "MCP connection failed"): Error {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
                return Error(msg, e.stackTraceToString().take(500))
            }
        }
    }

    val label: String
        get() = when (this) {
            is Idle -> "Idle"
            is Connecting -> "Connecting..."
            is Connected -> "Connected"
            is Reconnecting -> "Reconnecting ($attempt/$maxAttempts)..."
            is NeedsAuthorization -> "Needs Authorization"
            is Authorizing -> "Authorizing..."
            is Stopped -> "Stopped"
            is Error -> "Error: $message"
        }
}

/**
 * Thread-safe status store for all active MCP servers.
 */
class McpStatusStore {
    private val _status = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpStatus>> = _status.asStateFlow()

    fun get(configId: String): Flow<McpStatus> =
        status.map { it[configId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun update(configId: String, status: McpStatus) {
        _status.update { current -> current + (configId to status) }
    }

    fun remove(configId: String) {
        _status.update { current -> current - configId }
    }
}
