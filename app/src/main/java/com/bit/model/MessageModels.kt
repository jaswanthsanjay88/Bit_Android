package com.bit.model

enum class Participant { USER, MODEL, SYSTEM, ERROR }

enum class MessageStatus { SENDING, THINKING, TOOL_CALLING, TRANSCRIBING, SUCCESS, ERROR, STOPPED }

enum class RunStatus { ACTIVE, SUCCESS, ERROR, STOPPED }

data class ChatConversation(
    val id: String,
    val title: String = "",
    val modelId: String? = null,
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val parentId: String? = null,
    val text: String = "",
    val thoughts: String? = null,
    val participant: Participant = Participant.USER,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SUCCESS,
    val modelName: String? = null,
    val runId: String? = null,
    val runSequence: Int = 0,
    val consumedAtPass: Int = 0
)
