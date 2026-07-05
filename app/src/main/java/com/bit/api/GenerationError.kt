package com.bit.api

sealed class GenerationError {

    data class Network(
        val statusCode: Int,
        val message: String
    ) : GenerationError()

    data class Api(
        val code: String?,
        val type: String?,
        val message: String
    ) : GenerationError()

    data class SseParse(
        val rawLine: String,
        val cause: String
    ) : GenerationError()

    data class ToolExecution(
        val toolName: String,
        val arguments: String,
        val message: String
    ) : GenerationError()

    data class Transcription(
        val imagePath: String,
        val message: String
    ) : GenerationError()

    data class Embedding(
        val modelId: String,
        val message: String
    ) : GenerationError()

    data class LocalModel(
        val message: String
    ) : GenerationError()

    data class Configuration(
        val message: String
    ) : GenerationError()

    data class Unknown(
        val cause: Throwable
    ) : GenerationError()

    object Cancelled : GenerationError()

    object Timeout : GenerationError()

    fun userMessage(): String = when (this) {
        is Network -> when (statusCode) {
            401 -> "Authentication failed. Please check your API key."
            429 -> "Rate limit exceeded. Please wait and try again."
            in 500..599 -> "Server error ($statusCode). The service may be temporarily unavailable."
            else -> "Network error ($statusCode): $message"
        }
        is Api -> buildString {
            if (code != null) append("$code")
            if (type != null) append(" [$type]")
            if (isNotEmpty()) append(": ")
            append(message)
        }
        is SseParse -> "Failed to parse server response."
        is ToolExecution -> "Tool '$toolName' failed: $message"
        is Transcription -> "Image transcription failed: $message"
        is Embedding -> "Embedding failed: $message"
        is LocalModel -> message
        is Configuration -> message
        is Unknown -> cause.localizedMessage ?: "An unexpected error occurred."
        Cancelled -> "Generation cancelled."
        Timeout -> "Request timed out."
    }
}
