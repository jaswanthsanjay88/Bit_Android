package com.dark.gguf_lib

/**
 * Data class representing context window statistics.
 * Total, used, remaining, prompt token estimate, and expected context after prompt.
 */
data class ContextInfo(
    val total: Int,
    val used: Int,
    val remaining: Int,
    val promptEstimate: Int = -1,
    val afterPrompt: Int = -1
)
