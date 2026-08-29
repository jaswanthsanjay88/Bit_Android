package com.bit.agent.harness.engine

import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.tools.AgentToolRegistry
import com.bit.api.ChatMessage
import com.bit.api.LlmProviderResolver
import com.bit.api.Participant
import com.bit.api.ProviderConfig
import com.bit.api.StreamEvent
import com.bit.di.AppContainer
import com.bit.worker.ActiveModelSession
import kotlinx.coroutines.flow.FlowCollector
import org.json.JSONObject

import com.bit.engine.GenerationEvent
import com.bit.worker.LlmModelWorker

/**
 * Produces a raw JSON plan for a user goal. Returns null when planning is not
 * possible, in which case the engine falls back to heuristic decomposition.
 */
fun interface PlanGenerator {
    suspend fun generatePlanJson(goal: String): String?
}

/**
 * Production plan generator backed by BIT's active remote LLM provider.
 * Mirrors ChatViewModel's inference-config resolution; returns null whenever a
 * usable remote model is unavailable so the harness degrades to heuristics.
 */
class LlmGoalPlanner(
    private val toolRegistry: AgentToolRegistry? = null,
    private val logger: HarnessLogger = NoOpHarnessLogger
) : PlanGenerator {

    companion object {
        private const val TAG = "LlmGoalPlanner"
        private const val MAX_PLAN_TOKENS = 1024
    }

    override suspend fun generatePlanJson(goal: String): String? {
        val planningPrompt = buildPlanningPrompt(goal)

        // 1. Try local GGUF model if loaded
        if (LlmModelWorker.isGgufModelLoaded.value) {
            try {
                val messages = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an autonomous task planner. Return ONLY a valid JSON array of steps without any other text or markdown fences.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", planningPrompt)
                    })
                }
                val builder = StringBuilder()
                LlmModelWorker.ggufGenerateMultiTurnStreaming(messages.toString(), maxTokens = MAX_PLAN_TOKENS).collect { event ->
                    if (event is GenerationEvent.Token) {
                        builder.append(event.text)
                    }
                }
                val text = builder.toString().trim()
                if (text.isNotBlank()) {
                    val cleanJson = if (text.contains("[")) {
                        "[" + text.substringAfter("[").substringBeforeLast("]") + "]"
                    } else text
                    return cleanJson
                }
            } catch (e: Exception) {
                logger.w(TAG, "Local GGUF planning failed: ${e.message}")
            }
        }

        // 2. Try remote API provider
        val cfg = resolveInferenceConfig()
        if (cfg != null) {
            return try {
                val messages = listOf(
                    ChatMessage(text = planningPrompt, participant = Participant.USER)
                )
                val builder = StringBuilder()
                cfg.provider.generateResponse(messages, cfg.config).collect { event ->
                    if (event is StreamEvent.TextChunk) builder.append(event.text)
                }
                val text = builder.toString().trim()
                if (text.isNotBlank()) {
                    val cleanJson = if (text.contains("[")) {
                        "[" + text.substringAfter("[").substringBeforeLast("]") + "]"
                    } else text
                    cleanJson
                } else null
            } catch (e: Exception) {
                logger.w(TAG, "Remote LLM planning failed: ${e.message}")
                null
            }
        }

        return null
    }

    private data class InferenceSetup(val provider: com.bit.api.LlmProvider, val config: ProviderConfig)

    private suspend fun resolveInferenceConfig(): InferenceSetup? {
        var modelId = ActiveModelSession.currentModelId.value
        if (modelId.isBlank()) {
            modelId = LlmModelWorker.currentGgufModelId.value ?: ""
        }
        if (modelId.isBlank()) return null

        val repoConfig = AppContainer.getModelRepository().getConfigByModelId(modelId) ?: return null
        val loading = repoConfig.modelLoadingParams?.takeIf { it.isNotBlank() } ?: return null
        val json = try {
            JSONObject(loading)
        } catch (_: Exception) {
            return null
        }
        val endpoint = json.optString("endpoint").trim()
        if (endpoint.isBlank()) return null

        val model = json.optString("model").takeIf { it.isNotBlank() } ?: modelId
        val auth = json.optString("authHeader").takeIf { it.isNotBlank() }
            ?: json.optString("authorization")

        val provider = LlmProviderResolver.resolveProvider(endpoint, model)
        val config = ProviderConfig(
            apiKey = LlmProviderResolver.cleanApiKey(auth),
            modelId = model,
            baseUrl = LlmProviderResolver.cleanBaseUrl(endpoint),
            maxTokens = MAX_PLAN_TOKENS,
            thinkingEnabled = false
        )
        return InferenceSetup(provider, config)
    }

    private fun buildPlanningPrompt(goal: String): String {
        val toolNames = toolRegistry?.names().orEmpty()
        val toolList = if (toolNames.isEmpty()) {
            "web_search, workspace_write_file, workspace_shell, workspace_read_file, create_memory_file, invoke_subagent"
        } else {
            toolNames.joinToString(", ")
        }
        return buildString {
            appendLine("You are an autonomous agent planner. Decompose the user goal into actionable tool steps.")
            appendLine("Generate the FULL, COMPLETE content (code, script, or text) inside the tool arguments.")
            appendLine("Respond with ONLY a JSON array, no conversational prose, no markdown fences. Each element must follow:")
            appendLine("""[
  {
    "id": "step_1",
    "description": "Brief description of the step",
    "toolName": "<tool name from allowed tools>",
    "arguments": {
      "path": "filename.py",
      "text": "full python code here",
      "command": "python3 filename.py",
      "query": "search keywords",
      "title": "note title",
      "content": "note content"
    },
    "expectedOutcome": "Expected outcome of the step"
  }
]""")
            appendLine()
            appendLine("Allowed tools: [$toolList]")
            appendLine()
            appendLine("PLANNING RULES:")
            appendLine("- Multi-part goals (e.g. \"research X, then have a reviewer verify\") MUST become separate sequential steps — never merge phases into one step.")
            appendLine("- If the goal asks for verification, review, fact-checking, or auditing of findings, add a final step using toolName \"invoke_subagent\" with arguments: {\"role\": \"Verification Reviewer\", \"goal\": \"<what to verify and how>\", \"max_steps\": 8}.")
            appendLine("- invoke_subagent automatically receives all prior step outputs appended to its goal — phrase its goal as work instructions (e.g. \"Cross-check the supplied findings against sources; flag unsupported claims\").")
            appendLine("- INDEPENDENT parallel sub-tasks (e.g. verify unrelated claim groups, research separate subtopics) may be batched in ONE invoke_subagent step via a \"tasks\" array: {\"tasks\":[{\"role\":\"...\",\"goal\":\"...\",\"max_steps\":8}]}. Max 3 tasks; they run concurrently. Never batch dependent tasks.")
            appendLine("- After a web_search step whose findings will be verified, add a Research Reader invoke_subagent step that uses web_fetch on the 2-3 top URLs and outputs FINDINGS + a numbered CLAIMS list. Reviewers verify those claims — never raw search snippets.")
            appendLine("- Reviewer subagents must END their report with: (a) a corrections list marking every fixable issue CORRECTION REQUIRED or ADDITION REQUIRED with the corrected statement, and (b) a final verdict: PASS, PASS_WITH_CONDITIONS, or FAIL. The harness auto-injects revision + convergence steps when corrections are demanded.")
            appendLine("- Search queries must be short keyword phrases extracted from the goal — NEVER copy the full instruction sentence into a query, and do NOT invent years, dates or limits the user did not specify.")
            appendLine("- Keep plans under 10 steps. Prefer fewer, well-scoped steps.")
            appendLine()
            appendLine("""Example verification step:
  {"id": "step_2", "description": "Verify findings with an independent reviewer", "toolName": "invoke_subagent", "arguments": {"role": "Verification Reviewer", "goal": "Cross-check the research findings about <topic> using web_search and web_fetch; flag unsupported claims.", "max_steps": 8}, "expectedOutcome": "Findings verified"}""")
            appendLine()
            appendLine("User Goal: $goal")
        }
    }
}
