package com.bit.agent.harness.engine

import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.SubagentResult
import com.bit.agent.harness.model.SubagentTask
import com.bit.agent.harness.tools.SubagentExecutor
import com.bit.api.ChatMessage
import com.bit.api.LlmProviderResolver
import com.bit.api.Participant
import com.bit.api.ProviderConfig
import com.bit.api.StreamEvent
import com.bit.api.ToolCallData
import com.bit.plugins.PluginManager
import com.bit.worker.ActiveModelSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Real multi-agent execution: runs an isolated agentic loop for a [SubagentTask] using
 * the active remote LLM provider and the enabled PluginManager tool suite.
 *
 * The subagent has its own message history, a hard step budget, and cannot recurse into
 * further subagents. Tool results are appended back so the model can iterate until it
 * produces a final synthesized answer.
 */
class SubagentRunner(
    private val logger: HarnessLogger = NoOpHarnessLogger
) : SubagentExecutor {

    companion object {
        private const val TAG = "SubagentRunner"
        private const val FORBIDDEN_TOOL = "invoke_subagent"
        private const val MAX_SUB_STEPS = 20

        /** Text-only rounds shorter than this are treated as mid-work chatter, not the final report. */
        private const val MIN_FINAL_ANSWER_CHARS = 100

        /** Concurrency cap for parallel fan-out (device LLM/API friendliness). */
        private const val MAX_PARALLEL_SUBAGENTS = 3
    }

    /**
     * Parallel fan-out: runs independent subagent tasks concurrently, capped by
     * [MAX_PARALLEL_SUBAGENTS] so on-device/network resources are not saturated.
     */
    override suspend fun executeAll(tasks: List<SubagentTask>): List<SubagentResult> =
        withContext(Dispatchers.IO) {
            val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_SUBAGENTS)
            coroutineScope {
                tasks.map { task ->
                    async { semaphore.withPermit { execute(task) } }
                }.awaitAll()
            }
        }

    override suspend fun execute(task: SubagentTask): SubagentResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val remoteCfg = resolveRemoteConfig()
                ?: return@withContext SubagentResult(
                    taskId = task.id,
                    role = task.role,
                    isSuccess = false,
                    summary = "No API model configured; subagents require a remote endpoint.",
                    output = "Configure an API provider (endpoint + auth) to deploy subagents. " +
                        "Local GGUF models do not support isolated subagent loops yet.",
                    artifacts = emptyList(),
                    stepsCompleted = 0
                )

            val provider = LlmProviderResolver.resolveProvider(remoteCfg.endpoint, remoteCfg.model)
            val apiKey = LlmProviderResolver.cleanApiKey(remoteCfg.authHeader)
            val baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint)

            val maxSteps = task.maxSteps.coerceIn(1, MAX_SUB_STEPS)
            val jsonSerializer = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val tools = PluginManager.getEnabledToolDefinitions()
                .mapNotNull { builder ->
                    runCatching {
                        val defJson = builder.build().toOpenAIFormat()
                        val fnName = defJson.optJSONObject("function")?.optString("name").orEmpty()
                        if (fnName.isBlank() || fnName.equals(FORBIDDEN_TOOL, ignoreCase = true)) {
                            null
                        } else {
                            jsonSerializer.decodeFromString<com.bit.api.ToolDefinition>(defJson.toString())
                        }
                    }.getOrNull()
                }

            val systemPrompt = buildSystemPrompt(task)
            val chatMessages = mutableListOf<ChatMessage>()
            val artifacts = mutableListOf<String>()
            var stepsCompleted = 0
            var finalText = ""
            var endedCleanly = false

            // Live session tracking so the UI can show this subagent working for the user
            SubagentSessionBus.start(task.id, task.role, task.goal, maxSteps)

            while (stepsCompleted < maxSteps) {
                stepsCompleted++
                val config = ProviderConfig(
                    apiKey = apiKey,
                    modelId = remoteCfg.model,
                    systemPrompt = systemPrompt,
                    baseUrl = baseUrl,
                    tools = tools.ifEmpty { null },
                    thinkingEnabled = false,
                    maxTokens = 2048
                )

                val textBuilder = StringBuilder()
                val pendingCalls = mutableListOf<Triple<String, String, String?>>() // name, args, id

                provider.generateResponse(chatMessages.toList(), config).collect { event ->
                    when (event) {
                        is StreamEvent.TextChunk -> textBuilder.append(event.text)
                        is StreamEvent.ToolCallRequest ->
                            pendingCalls.add(Triple(event.name, event.arguments, event.id))
                        is StreamEvent.ToolCallsRequest -> event.calls.forEach { call ->
                            pendingCalls.add(Triple(call.name, call.arguments, call.id))
                        }
                        else -> Unit
                    }
                }

                if (pendingCalls.isEmpty()) {
                    // Text-only round: accept as final ONLY if it is substantial enough to be a
                    // real report. Small models often emit mid-work reasoning as plain text in
                    // one round and intend tool calls in the next — ending the loop on that
                    // chatter produces fragment outputs like "Let's try alternate source.".
                    val candidate = stripThinking(textBuilder.toString())
                    SubagentSessionBus.log(task.id, stepsCompleted, "Reasoned (${candidate.length} chars, no tools)")
                    if (candidate.length >= MIN_FINAL_ANSWER_CHARS) {
                        finalText = candidate
                        endedCleanly = true
                        break
                    }
                    chatMessages.add(ChatMessage(text = candidate, participant = Participant.MODEL))
                    chatMessages.add(
                        ChatMessage(
                            text = "That was intermediate reasoning, not your final report. " +
                                    "Continue: either call a tool to gather more evidence, or write " +
                                    "the complete final report now (verified claims, contradictions, uncertainties, conclusion).",
                            participant = Participant.USER
                        )
                    )
                    logger.d(TAG, "Short text-only round (${candidate.length} chars) treated as chatter; nudging continuation")
                    continue
                }

                // Execute tool calls sequentially in the isolated context
                var executedAny = false
                for ((name, argsJson, callId) in pendingCalls) {
                    if (name.equals(FORBIDDEN_TOOL, ignoreCase = true)) continue
                    val safeArgs = sanitizeArguments(argsJson)
                    SubagentSessionBus.log(
                        task.id, stepsCompleted,
                        "Calling $name(${safeArgs.toString().take(120)}${if (safeArgs.toString().length > 120) "…" else ""})"
                    )
                    val result = PluginManager.executeToolForMultiTurn(
                        com.dark.gguf_lib.toolcalling.ToolCall(name = name, arguments = safeArgs),
                        context = null,
                        callId = callId ?: UUID.randomUUID().toString()
                    )
                    extractArtifacts(safeArgs, result.resultJson).forEach { artifact ->
                        if (artifact !in artifacts) artifacts.add(artifact)
                    }
                    SubagentSessionBus.log(
                        task.id, stepsCompleted,
                        (if (result.isError) "✗ " else "✓ ") + "$name finished (${result.resultJson.length} chars)"
                    )

                    val toolCallData = ToolCallData(
                        toolName = name,
                        arguments = safeArgs.toString(),
                        result = result.resultJson,
                        toolCallId = callId ?: com.bit.api.util.buildToolCallId(name, safeArgs.toString())
                    )
                    chatMessages.add(
                        ChatMessage(
                            id = com.bit.api.Constants.TOOL_MSG_PREFIX + UUID.randomUUID(),
                            text = "",
                            participant = Participant.MODEL,
                            toolCall = toolCallData
                        )
                    )
                    chatMessages.add(
                        ChatMessage(
                            id = com.bit.api.Constants.RESULT_MSG_PREFIX + UUID.randomUUID(),
                            text = "",
                            participant = Participant.USER,
                            toolCall = toolCallData
                        )
                    )
                    executedAny = true
                }

                // All emitted calls were filtered (e.g. nested subagent attempts) — nothing new
                // entered the context, so the model would spin. Force a decision instead.
                if (!executedAny) {
                    chatMessages.add(
                        ChatMessage(
                            text = "No valid tools were executed this round. Based on everything gathered so far, write your complete final report now.",
                            participant = Participant.USER
                        )
                    )
                }
            }

            // Budget exhausted mid-work: force a final synthesis pass with tools disabled so the
            // parent agent receives a real report instead of raw mid-task reasoning.
            if (!endedCleanly && chatMessages.isNotEmpty()) {
                logger.d(TAG, "Step budget exhausted; forcing final synthesis for [${task.role}]")
                SubagentSessionBus.log(task.id, stepsCompleted, "Step budget reached — writing final report")
                chatMessages.add(
                    ChatMessage(
                        text = "STEP BUDGET REACHED. Stop investigating. Based ONLY on the tool results " +
                                "collected above, write your FINAL report now: what was verified, what is " +
                                "contradicted or uncertain, and your conclusion. No tool calls.",
                        participant = Participant.USER
                    )
                )
                val synthConfig = ProviderConfig(
                    apiKey = apiKey,
                    modelId = remoteCfg.model,
                    systemPrompt = systemPrompt,
                    baseUrl = baseUrl,
                    tools = null,
                    thinkingEnabled = false,
                    maxTokens = 1024
                )
                val synthBuilder = StringBuilder()
                runCatching {
                    provider.generateResponse(chatMessages.toList(), synthConfig).collect { event ->
                        if (event is StreamEvent.TextChunk) synthBuilder.append(event.text)
                    }
                }.onFailure { e -> logger.w(TAG, "Synthesis pass failed: ${e.message}") }
                val synthesized = stripThinking(synthBuilder.toString())
                if (synthesized.isNotBlank()) finalText = synthesized
            }

            val success = finalText.isNotBlank() || artifacts.isNotEmpty()
            val extractedClaims = ClaimExtractor.extract(finalText)
            if (extractedClaims.isNotEmpty()) {
                logger.d(TAG, "Extracted ${extractedClaims.size} structured claims from [${task.role}] report")
            }
            SubagentSessionBus.finish(
                id = task.id,
                status = when {
                    success && endedCleanly -> "COMPLETED"
                    success -> "COMPLETED"
                    else -> "FAILED"
                },
                result = finalText.ifBlank { "No final text produced." }
            )
            if (extractedClaims.isNotEmpty()) {
                SubagentSessionBus.log(task.id, stepsCompleted, "Report structured: ${extractedClaims.size} claims extracted")
            }
            SubagentResult(
                taskId = task.id,
                role = task.role,
                isSuccess = success,
                summary = if (endedCleanly) {
                    "Completed in $stepsCompleted step(s)"
                } else if (success) {
                    "Budget-capped after $stepsCompleted steps; forced synthesis applied"
                } else {
                    "Exhausted $maxSteps step budget without a final answer"
                },
                output = finalText.ifBlank { "No final text produced." },
                artifacts = artifacts,
                stepsCompleted = stepsCompleted,
                claims = extractedClaims
            ).also {
                logger.d(TAG, "Subagent [${task.role}] finished in ${System.currentTimeMillis() - startTime}ms")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Subagent [${task.role}] failed: ${e.message}", e)
            SubagentSessionBus.finish(task.id, "FAILED", "Subagent crashed: ${e.message ?: "unknown error"}")
            SubagentResult(
                taskId = task.id,
                role = task.role,
                isSuccess = false,
                summary = "Subagent crashed: ${e.message ?: "unknown error"}",
                output = e.stackTraceToString().take(2000),
                artifacts = emptyList(),
                stepsCompleted = 0
            )
        }
    }

    private data class RemoteEndpointConfig(val endpoint: String, val model: String, val authHeader: String?)

    /**
     * Mirrors ChatViewModel.getRemoteInferenceConfig(): resolves the active model's
     * endpoint/model/auth from the ModelRepository loading params.
     */
    private suspend fun resolveRemoteConfig(): RemoteEndpointConfig? {
        val modelId = ActiveModelSession.currentModelId.value.ifBlank { return null }
        val config = runCatching {
            AppContainerAccess.getModelRepository().getConfigByModelId(modelId)
        }.getOrNull() ?: return null
        val loading = config.modelLoadingParams?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val json = JSONObject(loading)
            val endpoint = json.optString("endpoint").trim()
            if (endpoint.isBlank()) return null
            RemoteEndpointConfig(
                endpoint = endpoint,
                model = json.optString("model").takeIf { it.isNotBlank() } ?: modelId,
                authHeader = json.optString("authHeader").takeIf { it.isNotBlank() }
                    ?: json.optString("authorization").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Removes <think> reasoning blocks so mid-task thoughts never leak into reports. */
    private fun stripThinking(raw: String): String = raw
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun sanitizeArguments(raw: String): JSONObject = try {
        when {
            raw.isBlank() -> JSONObject()
            raw.trim().startsWith("{") -> JSONObject(raw.trim())
            else -> JSONObject(mapOf("input" to raw))
        }
    } catch (_: Exception) {
        JSONObject()
    }

    private fun extractArtifacts(args: JSONObject, resultJson: String): List<String> {
        val out = mutableListOf<String>()
        listOf("path", "file_path", "url").forEach { key ->
            args.optString(key).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        try {
            val res = JSONObject(resultJson)
            listOf("path", "url").forEach { key ->
                res.optString(key).takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        } catch (_: Exception) {}
        return out.distinct()
    }

    private fun buildSystemPrompt(task: SubagentTask): String {
        return buildString {
            appendLine("You are an autonomous specialized subagent deployed inside BIT's agent harness.")
            appendLine("ROLE: ${task.role}")
            appendLine()
            appendLine("MISSION: ${task.goal}")
            appendLine()
            appendLine("RULES:")
            appendLine("- You operate in an isolated context; the parent agent only sees your final report.")
            appendLine("- Use the available tools to accomplish the mission.")
            appendLine("- You have a strict step budget of ${task.maxSteps} turns; be efficient.")
            appendLine("- When the mission is complete, reply with ONLY the concise final report (no tool calls).")
            appendLine("- Never attempt to deploy further subagents.")
            appendLine()
            appendLine("RECOMMENDED OUTPUT FORMAT for verification/review reports:")
            appendLine("Write the human-readable report first, then end with a JSON block:")
            appendLine("```json")
            appendLine("{\"claims\":[{\"claim\":\"<the claim>\",\"status\":\"VERIFIED|CONTRADICTED|UNCERTAIN\",\"sources\":[\"<url or name>\"],\"notes\":\"<evidence summary>\"}]}")
            appendLine("```")
        }.trimEnd()
    }
}

/** Indirection over AppContainer to keep this file decoupled from DI graph internals. */
private object AppContainerAccess {
    fun getModelRepository(): com.bit.repo.ModelRepository =
        com.bit.di.AppContainer.getModelRepository()
}
