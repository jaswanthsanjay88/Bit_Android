package com.bit.api.util

class StreamingThinkTagParser(
    /**
     * When true, the parser starts in "inside thinking block" state.
     * This handles models whose chat template pre-fills
     * `<think>\n` in the prompt so the generated stream starts
     * already inside a thinking block — only `</think>` appears.
     */
    assumeThinking: Boolean = false
) {
    var inThinkingBlock = assumeThinking
    var pendingBuffer = ""
    private var hasExitedThinkBlock = false

    private val tagPairs = listOf(
        "<think>" to "</think>",
        "[THINK]" to "[/THINK]",
        "<reasoning>" to "</reasoning>",
        "<|channel>thought" to "<|channel>"
    )
    private var activeCloseTag: String = "</think>"

    suspend fun feed(
        content: String,
        thinkingEnabled: Boolean = true,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit
    ) {
        pendingBuffer += content

        while (pendingBuffer.isNotEmpty()) {
            if (!inThinkingBlock) {
                if (!hasExitedThinkBlock) {
                    var earliestOpenIdx = -1
                    var matchedOpenTag = ""
                    var matchedCloseTag = ""

                    for ((open, close) in tagPairs) {
                        val idx = pendingBuffer.indexOf(open)
                        if (idx != -1 && (earliestOpenIdx == -1 || idx < earliestOpenIdx)) {
                            earliestOpenIdx = idx
                            matchedOpenTag = open
                            matchedCloseTag = close
                        }
                    }

                    if (earliestOpenIdx != -1) {
                        val before = pendingBuffer.substring(0, earliestOpenIdx)
                        if (before.isNotEmpty()) onText(before)
                        inThinkingBlock = true
                        activeCloseTag = matchedCloseTag
                        pendingBuffer = pendingBuffer.substring(earliestOpenIdx + matchedOpenTag.length)
                        continue
                    }

                    // Check for orphan closing tag
                    var orphanCloseIdx = -1
                    var matchedOrphanClose = ""
                    for ((_, close) in tagPairs) {
                        val idx = pendingBuffer.indexOf(close)
                        if (idx != -1 && (orphanCloseIdx == -1 || idx < orphanCloseIdx)) {
                            orphanCloseIdx = idx
                            matchedOrphanClose = close
                        }
                    }

                    if (orphanCloseIdx != -1) {
                        val thought = pendingBuffer.substring(0, orphanCloseIdx)
                        if (thought.isNotEmpty()) onThought(thought)
                        hasExitedThinkBlock = true
                        pendingBuffer = pendingBuffer.substring(orphanCloseIdx + matchedOrphanClose.length)
                        continue
                    }
                }

                // Check if the end of pendingBuffer might be a prefix of any opening tag (e.g. "<thi")
                var potentialPrefixStart = -1
                if (!hasExitedThinkBlock) {
                    for ((open, _) in tagPairs) {
                        val firstChar = open.first()
                        var searchIdx = pendingBuffer.indexOf(firstChar)
                        while (searchIdx != -1) {
                            val candidate = pendingBuffer.substring(searchIdx)
                            if (open.startsWith(candidate)) {
                                if (potentialPrefixStart == -1 || searchIdx < potentialPrefixStart) {
                                    potentialPrefixStart = searchIdx
                                }
                                break
                            }
                            searchIdx = pendingBuffer.indexOf(firstChar, searchIdx + 1)
                        }
                    }
                }

                if (potentialPrefixStart != -1) {
                    val before = pendingBuffer.substring(0, potentialPrefixStart)
                    if (before.isNotEmpty()) onText(before)
                    pendingBuffer = pendingBuffer.substring(potentialPrefixStart)
                    break
                } else {
                    onText(pendingBuffer)
                    pendingBuffer = ""
                }
            } else {
                val endIdx = pendingBuffer.indexOf(activeCloseTag)
                if (endIdx != -1) {
                    val thought = pendingBuffer.substring(0, endIdx)
                    if (thought.isNotEmpty()) onThought(thought)
                    inThinkingBlock = false
                    hasExitedThinkBlock = true
                    pendingBuffer = pendingBuffer.substring(endIdx + activeCloseTag.length)
                } else {
                    // Check if the end of pendingBuffer is a prefix of activeCloseTag
                    val firstChar = activeCloseTag.first()
                    var searchIdx = pendingBuffer.indexOf(firstChar)
                    var potentialClosePrefix = -1
                    while (searchIdx != -1) {
                        val candidate = pendingBuffer.substring(searchIdx)
                        if (activeCloseTag.startsWith(candidate)) {
                            potentialClosePrefix = searchIdx
                            break
                        }
                        searchIdx = pendingBuffer.indexOf(firstChar, searchIdx + 1)
                    }

                    if (potentialClosePrefix != -1) {
                        val before = pendingBuffer.substring(0, potentialClosePrefix)
                        if (before.isNotEmpty()) onThought(before)
                        pendingBuffer = pendingBuffer.substring(potentialClosePrefix)
                        break
                    } else {
                        onThought(pendingBuffer)
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
