package com.bit.api.openai

import com.bit.api.*
import com.bit.api.util.StreamingThinkTagParser
import com.bit.api.util.convertToOpenAiMessages
import com.bit.api.util.prepareMessages
import com.bit.api.util.ProviderRetryPolicy
import com.bit.api.util.asRetryableTransportError
import com.bit.network.HttpClient
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseOpenAiProvider : LlmProvider {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request

    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()

    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningContent?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
        }
    }

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    protected open val retryMissingV1BaseUrl: Boolean = false

    protected open fun retryDelayMillis(statusCode: Int, attempt: Int): Long = ProviderRetryPolicy.delayMillis(attempt)

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")

        val validatedMessages = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = convertToOpenAiMessages(
            messages = validatedMessages,
            systemPrompt = transformSystemPrompt(config.systemPrompt),
            includeImages = config.includeImages
        )

        var request = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = if (name == Constants.PROVIDER_OPENAI || name == Constants.PROVIDER_OPEN_ROUTER) OpenAiStreamOptions(includeUsage = true) else null,
            tools = config.tools,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        request = customizeRequest(request, config)

        val thinkParser = StreamingThinkTagParser()

        try {
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            Log.d("AgoraAPI", "[$name] REQ -> ${endpointUrls.first()} | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")

            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = ProviderRetryPolicy.MAX_ATTEMPTS
            var attempt = 0
            var finished = false

            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false

                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    val handle = try {
                        HttpClient.streamPost(endpointUrl, requestBodyJson, headers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val retryable = e.asRetryableTransportError()
                        if (retryable != null && attempt < maxAttempts) {
                            Log.w("AgoraAPI", "[$name] Transport error on attempt $attempt/$maxAttempts (${e.javaClass.simpleName}), retrying...")
                            emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                            delay(ProviderRetryPolicy.delayMillis(attempt))
                            retryScheduled = true
                            continue
                        }
                        throw e
                    }
                    try {
                        if (handle.code == 200) {
                            consumeSuccessfulStream(handle, config, thinkParser) { emit(it) }
                            finished = true
                        } else {
                            val errorRaw = handle.errorBody ?: "Unknown error"
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                Log.w("AgoraAPI", "[$name] ${handle.code} at $endpointUrl, retrying with ${endpointUrls[endpointIndex + 1]}")
                                endpointIndex++
                                continue
                            }

                            Log.e("AgoraAPI", "[$name] ERR ${handle.code} at $endpointUrl: $errorRaw")

                            if (handle.code in retryableStatusCodes && attempt < maxAttempts) {
                                val retryDelayMs = ProviderRetryPolicy.delayMillis(attempt)
                                Log.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, ProviderRetryPolicy.MAX_RETRIES))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        val toolParser = com.bit.api.util.StreamingToolParser()

        val customEmitter: suspend (StreamEvent) -> Unit = { event ->
            if (event is StreamEvent.TextChunk) {
                toolParser.feed(
                    content = event.text,
                    onText = { emit(StreamEvent.TextChunk(it)) },
                    onToolCall = { name, args ->
                        emit(StreamEvent.ToolCallRequest(java.util.UUID.randomUUID().toString(), name, args))
                    }
                )
            } else {
                emit(event)
            }
        }

        while (currentCoroutineContext().isActive) {
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                throw e
            } ?: break

            if (!line.startsWith("data:")) continue
            val jsonStr = line.substring(5).trim()
            if (jsonStr == "[DONE]") break

            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                val choice = response.choices?.firstOrNull()

                choice?.delta?.let { delta ->
                    parseDeltaContent(delta, config, thinkParser) { customEmitter(it) }

                    delta.toolCalls?.forEach { tc ->
                        val existing = if (tc.id != null) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
                        val pending = if (existing != null) existing else {
                            val idx = tc.index ?: pendingToolCalls.size
                            pendingToolCalls.getOrPut(idx) { PendingToolCall() }
                        }
                        if (tc.id != null) pending.id = tc.id
                        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
                        tc.function?.arguments?.let {
                            pending.args.append(if (it is JsonPrimitive) it.content else it.toString())
                        }
                    }
                }

                if (choice?.finishReason == "tool_calls" && pendingToolCalls.isNotEmpty()) {
                    val calls = pendingToolCalls.values.filter { it.name.isNotEmpty() }.map {
                        StreamEvent.ToolCallRequest(it.id, it.name, it.args.toString())
                    }
                    pendingToolCalls.clear()
                    if (calls.size == 1) emit(calls.first())
                    else if (calls.size > 1) emit(StreamEvent.ToolCallsRequest(calls))
                }

                response.usage?.let { usage ->
                    emit(
                        StreamEvent.UsageUpdate(
                            tokenCount = usage.totalTokens,
                            thoughtsTokenCount = usage.completionTokensDetails?.reasoningTokens ?: 0
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("AgoraAPI", "Parse error: ${e.message}", e)
            }
        }

        thinkParser.flush(
            onText = { customEmitter(StreamEvent.TextChunk(it)) },
            onThought = { emit(StreamEvent.ThoughtChunk(it)) }
        )
        
        toolParser.flush(
            onText = { emit(StreamEvent.TextChunk(it)) }
        )

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val primary = "$normalizedBaseUrl/$cleanPath"
        if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() ||
            BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)
        ) {
            return listOf(primary)
        }
        return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
    }

    private fun buildGenerationError(
        statusCode: Int,
        errorRaw: String,
        endpointUrls: List<String>
    ): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else {
            ""
        }
        return try {
            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
            GenerationError.Api(
                code = errorJson.error.code ?: statusCode.toString(),
                type = errorJson.error.type,
                message = errorJson.error.message + endpointHint
            )
        } catch (_: Exception) {
            GenerationError.Network(statusCode = statusCode, message = errorRaw + endpointHint)
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val endpointUrls = endpointCandidates(effectiveBaseUrl, "models")
            val headers = authHeaders(apiKey)
            var lastParseError: Exception? = null

            for ((index, endpointUrl) in endpointUrls.withIndex()) {
                val responseText = HttpClient.fetchModels(endpointUrl, headers)
                if (responseText == null) {
                    if (index < endpointUrls.lastIndex) {
                        Log.w("AgoraAPI", "Failed to fetch $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                    continue
                }

                try {
                    return@withContext json.decodeFromString<OpenAiModelListResponse>(responseText)
                        .data.map { it.id }.sorted()
                } catch (e: Exception) {
                    lastParseError = e
                    if (index < endpointUrls.lastIndex) {
                        Log.w("AgoraAPI", "Failed to parse $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                }
            }

            if (lastParseError != null) {
                Log.e("AgoraAPI", "Failed to parse $name models", lastParseError)
            } else {
                Log.e("AgoraAPI", "Failed to fetch $name models: empty response")
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("AgoraAPI", "Failed to fetch $name models", e)
            emptyList()
        }
    }
}
