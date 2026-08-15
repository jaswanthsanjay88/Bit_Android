package com.bit.mcp

import org.json.JSONObject
import java.util.UUID

/**
 * Data models for MCP (Model Context Protocol) integration.
 */
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val isEnabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val tools: List<McpToolConfig> = emptyList(),
    val status: McpStatus = McpStatus.Idle
)

data class McpToolConfig(
    val name: String = "",
    val description: String = "",
    val isEnabled: Boolean = true,
    val inputSchemaJson: String = "{}"
)

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Error(val message: String) : McpStatus()

    val label: String
        get() = when (this) {
            is Idle -> "Idle"
            is Connecting -> "Connecting..."
            is Connected -> "Connected"
            is Error -> "Error: $message"
        }
}
