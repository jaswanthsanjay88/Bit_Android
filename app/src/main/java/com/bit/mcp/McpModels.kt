package com.bit.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val oauth: McpOAuthState? = null,
)

/**
 * OAuth 2.1 authorization state following the MCP authorization specification (2025-11-25).
 * Persists dynamic client registration results, authorization server endpoints, and tokens
 * for injecting `Authorization: Bearer` headers and handling token refresh.
 */
@Serializable
data class McpOAuthState(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L, // epoch millis, 0 = unknown/does not expire
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank()

    override fun toString(): String =
        "McpOAuthState(enabled=$enabled, clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "registrationEndpoint=$registrationEndpoint, scope=$scope, " +
            "accessToken=${accessToken.masked()}, refreshToken=${refreshToken.masked()}, expiresAt=$expiresAt)"

    private fun String?.masked(): String = when {
        this == null -> "null"
        isBlank() -> "***"
        else -> "***(${length})"
    }
}

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchemaJson: String = "{}",
    val needsApproval: Boolean = false
)

@Serializable
sealed class McpServerConfig {
    abstract val id: String
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: String = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: String = UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: String = UUID.randomUUID().toString(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: String, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}

/** Server URL canonical representation. */
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }

val McpServerConfig.name: String
    get() = commonOptions.name

val McpServerConfig.url: String
    get() = serverUrl

val McpServerConfig.isEnabled: Boolean
    get() = commonOptions.enable

val McpServerConfig.headers: Map<String, String>
    get() = commonOptions.headers.toMap()

val McpServerConfig.tools: List<McpTool>
    get() = commonOptions.tools

val McpTool.isEnabled: Boolean
    get() = enable


