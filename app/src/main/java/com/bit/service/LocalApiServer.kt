package com.bit.service

import android.util.Log
import com.bit.engine.GenerationEvent
import com.bit.worker.LlmModelWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-Kotlin local HTTP server exposing an OpenAI-compatible API on 127.0.0.1:8080.
 * Zero external web server dependencies (uses standard ServerSocket).
 */
object LocalApiServer {
    private const val TAG = "LocalApiServer"
    private const val PORT = 8080

    private var serverJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Server already running")
            return
        }

        serverJob = scope.launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "OpenAI API server started on http://127.0.0.1:$PORT")

                while (isRunning.get()) {
                    val socket = serverSocket.accept()
                    scope.launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server exception: ${e.message}", e)
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: Exception) {}
                isRunning.set(false)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "OpenAI API server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        var outputStream: OutputStream? = null
        var reader: BufferedReader? = null
        try {
            socket.soTimeout = 15000
            outputStream = socket.getOutputStream()
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // 1. Read HTTP request line
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val path = parts[1]

            // 2. Read headers to find Content-Length
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: ""
                if (currentLine.isBlank()) break
                if (currentLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = currentLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 3. Handle only POST /v1/chat/completions
            if (method.equals("POST", ignoreCase = true) && path == "/v1/chat/completions") {
                if (contentLength <= 0) {
                    writeError(outputStream, 400, "Bad Request: Content-Length required")
                    return
                }

                // Read payload
                val bodyChars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val res = reader.read(bodyChars, read, contentLength - read)
                    if (res == -1) break
                    read += res
                }
                val payload = String(bodyChars)

                // Parse OpenAI payload
                val json = JSONObject(payload)
                val messages = json.optJSONArray("messages") ?: JSONArray()
                val stream = json.optBoolean("stream", false)
                val maxTokens = json.optInt("max_tokens", 512)

                if (messages.length() == 0) {
                    writeError(outputStream, 400, "Bad Request: messages array cannot be empty")
                    return
                }

                // Verify LLM model is loaded locally
                if (!LlmModelWorker.isGgufModelLoaded.value) {
                    writeError(outputStream, 503, "Service Unavailable: GGUF model is not loaded in the app")
                    return
                }

                if (stream) {
                    // Send SSE HTTP headers
                    val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "\r\n"
                    outputStream.write(headers.toByteArray())
                    outputStream.flush()

                    val promptJsonString = messages.toString()
                    val responseId = "chatcmpl-${System.currentTimeMillis()}"

                    // Collect tokens from local model worker flow
                    LlmModelWorker.ggufGenerateMultiTurnStreaming(promptJsonString, maxTokens).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                val chunk = JSONObject().apply {
                                    put("id", responseId)
                                    put("object", "chat.completion.chunk")
                                    put("created", System.currentTimeMillis() / 1000)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("delta", JSONObject().put("content", event.text))
                                        put("finish_reason", JSONObject.NULL)
                                        put("index", 0)
                                    }))
                                }
                                outputStream.write("data: $chunk\n\n".toByteArray())
                                outputStream.flush()
                            }
                            is GenerationEvent.Error -> {
                                val errChunk = JSONObject().apply {
                                    put("error", JSONObject().put("message", event.message))
                                }
                                outputStream.write("data: $errChunk\n\n".toByteArray())
                                outputStream.flush()
                            }
                            is GenerationEvent.Done -> {
                                val finalChunk = JSONObject().apply {
                                    put("id", responseId)
                                    put("object", "chat.completion.chunk")
                                    put("created", System.currentTimeMillis() / 1000)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("delta", JSONObject())
                                        put("finish_reason", "stop")
                                        put("index", 0)
                                    }))
                                }
                                outputStream.write("data: $finalChunk\n\n".toByteArray())
                                outputStream.write("data: [DONE]\n\n".toByteArray())
                                outputStream.flush()
                            }
                            else -> {}
                        }
                    }
                } else {
                    // Non-streaming response
                    val promptJsonString = messages.toString()
                    val responseId = "chatcmpl-${System.currentTimeMillis()}"
                    val responseBuffer = StringBuilder()
                    var errorMessage: String? = null

                    LlmModelWorker.ggufGenerateMultiTurnStreaming(promptJsonString, maxTokens).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> responseBuffer.append(event.text)
                            is GenerationEvent.Error -> errorMessage = event.message
                            else -> {}
                        }
                    }

                    if (errorMessage != null) {
                        writeError(outputStream, 500, "Internal Server Error: $errorMessage")
                        return
                    }

                    val completion = JSONObject().apply {
                        put("id", responseId)
                        put("object", "chat.completion")
                        put("created", System.currentTimeMillis() / 1000)
                        put("model", "local-gguf")
                        put("choices", JSONArray().put(JSONObject().apply {
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", responseBuffer.toString())
                            })
                            put("finish_reason", "stop")
                            put("index", 0)
                        }))
                    }
                    val respBytes = completion.toString().toByteArray()
                    val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${respBytes.size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                    outputStream.write(responseHeaders.toByteArray())
                    outputStream.write(respBytes)
                    outputStream.flush()
                }
            } else {
                writeError(outputStream, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client socket: ${e.message}")
        } finally {
            try {
                reader?.close()
                outputStream?.close()
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun writeError(out: OutputStream, code: Int, message: String) {
        val statusText = when (code) {
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val errJson = JSONObject().apply {
            put("error", JSONObject().put("message", message).put("code", code))
        }
        val respBytes = errJson.toString().toByteArray()
        val headers = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${respBytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        try {
            out.write(headers.toByteArray())
            out.write(respBytes)
            out.flush()
        } catch (_: Exception) {}
    }
}
