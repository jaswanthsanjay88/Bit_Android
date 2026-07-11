package com.bit.api.util

class StreamingThinkTagParser(
    /**
     * When true, the parser starts in "inside thinking block" state.
     * This handles models like Qwen3 whose chat template pre-fills
     * `<think>\n` in the prompt so the generated stream starts
     * already inside a thinking block — only `</think>` appears.
     */
    assumeThinking: Boolean = false
) {
    var inThinkingBlock = assumeThinking
    var pendingBuffer = ""
    private var hasExitedThinkBlock = false

    suspend fun feed(
        content: String,
        thinkingEnabled: Boolean,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit
    ) {
        pendingBuffer += content

        while (pendingBuffer.isNotEmpty()) {
            if (!inThinkingBlock) {
                val startIdx = if (!hasExitedThinkBlock) pendingBuffer.indexOf("<think>") else -1
                if (startIdx != -1) {
                    val before = pendingBuffer.substring(0, startIdx)
                    if (before.isNotEmpty()) onText(before)
                    inThinkingBlock = true
                    pendingBuffer = pendingBuffer.substring(startIdx + 7)
                } else {
                    val lastBracket = pendingBuffer.lastIndexOf('<')
                    if (!hasExitedThinkBlock && lastBracket != -1 && "<think>".startsWith(pendingBuffer.substring(lastBracket))) {
                        val before = pendingBuffer.substring(0, lastBracket)
                        if (before.isNotEmpty()) onText(before)
                        pendingBuffer = pendingBuffer.substring(lastBracket)
                        break
                    } else {
                        onText(pendingBuffer)
                        pendingBuffer = ""
                    }
                }
            } else {
                val endIdx = pendingBuffer.indexOf("</think>")
                if (endIdx != -1) {
                    val thought = pendingBuffer.substring(0, endIdx)
                    if (thought.isNotEmpty() && thinkingEnabled) onThought(thought)
                    inThinkingBlock = false
                    hasExitedThinkBlock = true
                    pendingBuffer = pendingBuffer.substring(endIdx + 8)
                } else {
                    val lastBracket = pendingBuffer.lastIndexOf('<')
                    if (lastBracket != -1 && "</think>".startsWith(pendingBuffer.substring(lastBracket))) {
                        val before = pendingBuffer.substring(0, lastBracket)
                        if (before.isNotEmpty() && thinkingEnabled) onThought(before)
                        pendingBuffer = pendingBuffer.substring(lastBracket)
                        break
                    } else {
                        if (thinkingEnabled) onThought(pendingBuffer)
                        pendingBuffer = ""
                    }
                }
            }
        }
    }

    suspend fun flush(
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit
    ) {
        if (pendingBuffer.isNotEmpty()) {
            if (inThinkingBlock) onThought(pendingBuffer)
            else onText(pendingBuffer)
            pendingBuffer = ""
        }
    }
}
