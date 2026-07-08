package com.bit.api.util

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StreamingToolParser {
    var inToolBlock = false
    var pendingBuffer = ""
    private var toolContentBuffer = ""

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun feed(
        content: String,
        onText: suspend (String) -> Unit,
        onToolCall: suspend (name: String, arguments: String) -> Unit
    ) {
        pendingBuffer += content

        while (pendingBuffer.isNotEmpty()) {
            if (!inToolBlock) {
                val startIdx = pendingBuffer.indexOf("<tool_call>")
                if (startIdx != -1) {
                    val before = pendingBuffer.substring(0, startIdx)
                    if (before.isNotEmpty()) onText(before)
                    inToolBlock = true
                    toolContentBuffer = ""
                    pendingBuffer = pendingBuffer.substring(startIdx + 11) // length of <tool_call>
                } else {
                    val lastBracket = pendingBuffer.lastIndexOf('<')
                    if (lastBracket != -1 && "<tool_call>".startsWith(pendingBuffer.substring(lastBracket))) {
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
                val endIdx = pendingBuffer.indexOf("</tool_call>")
                if (endIdx != -1) {
                    toolContentBuffer += pendingBuffer.substring(0, endIdx)
                    parseAndEmitToolCall(toolContentBuffer, onToolCall)
                    inToolBlock = false
                    toolContentBuffer = ""
                    pendingBuffer = pendingBuffer.substring(endIdx + 12) // length of </tool_call>
                } else {
                    val lastBracket = pendingBuffer.lastIndexOf('<')
                    if (lastBracket != -1 && "</tool_call>".startsWith(pendingBuffer.substring(lastBracket))) {
                        val before = pendingBuffer.substring(0, lastBracket)
                        if (before.isNotEmpty()) toolContentBuffer += before
                        pendingBuffer = pendingBuffer.substring(lastBracket)
                        break
                    } else {
                        toolContentBuffer += pendingBuffer
                        pendingBuffer = ""
                    }
                }
            }
        }
    }

    private suspend fun parseAndEmitToolCall(
        jsonString: String,
        onToolCall: suspend (name: String, arguments: String) -> Unit
    ) {
        try {
            val jsonElement = json.parseToJsonElement(jsonString.trim())
            val name = jsonElement.jsonObject["name"]?.jsonPrimitive?.content ?: return
            val argumentsElement = jsonElement.jsonObject["arguments"] ?: kotlinx.serialization.json.JsonObject(emptyMap())
            onToolCall(name, argumentsElement.toString())
        } catch (e: Exception) {
            Log.e("StreamingToolParser", "Failed to parse tool call JSON: \$jsonString", e)
        }
    }

    suspend fun flush(
        onText: suspend (String) -> Unit
    ) {
        if (pendingBuffer.isNotEmpty()) {
            if (!inToolBlock) {
                onText(pendingBuffer)
            } else {
                // If we are stuck in a tool block that never closed, flush it as text
                onText("<tool_call>\$toolContentBuffer\$pendingBuffer")
            }
            pendingBuffer = ""
            toolContentBuffer = ""
        }
    }
}
