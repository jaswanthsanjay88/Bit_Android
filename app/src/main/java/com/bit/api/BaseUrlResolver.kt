package com.bit.api

object BaseUrlResolver {
    private val VERSION_SEGMENT = Regex("""/v\d""")

    fun hasVersionSegment(url: String): Boolean =
        VERSION_SEGMENT.containsMatchIn(url.trimEnd('/'))

    fun withV1(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.isBlank() || hasVersionSegment(trimmed)) trimmed else "$trimmed/v1"
    }
}
