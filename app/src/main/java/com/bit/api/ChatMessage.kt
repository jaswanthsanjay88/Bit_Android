package com.bit.api

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ToolCallData(
    val toolName: String,
    val arguments: String,
    val result: String,
    val signature: String? = null,
    val toolCallId: String? = null
)

@Serializable
data class MessageSegment(
    val type: String, // "answer", "thought", "tool", or "transcription"
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val signature: String? = null,
    val durationMs: Long? = null
)

enum class Participant {
    USER, MODEL, ERROR
}

enum class MessageStatus {
    TRANSCRIBING, SENDING, THINKING, TOOL_CALLING, SUCCESS, STOPPED, ERROR
}

@Serializable
data class AttachmentItem(
    val originalUri: String? = null,
    val type: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val imageIndex: Int? = null,
    val pageCount: Int? = null,
    val warning: String? = null,
    val textContent: String? = null,
    val transcription: String? = null
)

@Serializable
data class AttachmentMeta(val items: List<AttachmentItem> = emptyList())

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val base64Images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCall: ToolCallData? = null,
    val segments: List<MessageSegment>? = null,
    val attachmentMeta: AttachmentMeta? = null,
    val retryText: String? = null
)
