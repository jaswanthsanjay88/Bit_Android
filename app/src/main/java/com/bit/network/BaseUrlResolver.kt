package com.bit.network

/**
 * Resolves the OpenAI-compatible "should the Base URL include /v1?" ambiguity.
 */
object BaseUrlResolver {
    /**
     * Matches an API version segment anywhere in the path: `/v1`, `/v1beta`,
     * `/compatible-mode/v1`, `/v2`, … If present, the URL is already canonical and
     * must not have `/v1` appended.
     */
    private val VERSION_SEGMENT = Regex("""/v\d""")

    fun hasVersionSegment(url: String): Boolean =
        VERSION_SEGMENT.containsMatchIn(url.trimEnd('/'))

    /** Appends `/v1` unless the URL is blank or already carries a version segment. */
    fun withV1(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.isBlank() || hasVersionSegment(trimmed)) trimmed else "$trimmed/v1"
    }
}
