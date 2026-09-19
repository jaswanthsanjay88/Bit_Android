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
        private const val MAX_PLAN_TOKENS = 4096
    }

    override suspend fun generatePlanJson(goal: String): String? {
        // 1. Try local GGUF model if loaded
        if (LlmModelWorker.isGgufModelLoaded.value) {
            try {
                val slmPrompt = buildSlmPlanningPrompt(goal)
                val messages = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an autonomous task planner. Return ONLY a valid JSON array of steps without any other text, explanation, or markdown fences.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", slmPrompt)
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
                    val cleanJson = cleanSlmPlanJson(text)
                    if (!cleanJson.isNullOrBlank()) return cleanJson
                }
            } catch (e: Exception) {
                logger.w(TAG, "Local GGUF planning failed: ${e.message}")
            }
        }

        // 2. Try remote API provider
        val cfg = resolveInferenceConfig()
        if (cfg != null) {
            return try {
                val planningPrompt = buildPlanningPrompt(goal)
                val messages = listOf(
                    ChatMessage(text = planningPrompt, participant = Participant.USER)
                )
                val textBuilder = StringBuilder()
                val thoughtBuilder = StringBuilder()
                cfg.provider.generateResponse(messages, cfg.config).collect { event ->
                    when (event) {
                        is StreamEvent.TextChunk -> textBuilder.append(event.text)
                        is StreamEvent.ThoughtChunk -> thoughtBuilder.append(event.thought)
                        is StreamEvent.Error -> logger.w(TAG, "Planning stream error: ${event.message}")
                        else -> Unit
                    }
                }
                val raw = if (textBuilder.isNotBlank()) textBuilder.toString() else thoughtBuilder.toString()
                val clean = cleanSlmPlanJson(raw)
                clean ?: raw.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                logger.w(TAG, "Remote LLM planning failed: ${e.message}")
                null
            }
        }

        return null
    }

    private fun cleanSlmPlanJson(raw: String): String? {
        var text = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
            .trim()

        if (text.contains("```")) {
            text = text.replace(Regex("""^```(?:json)?\s*""", RegexOption.MULTILINE), "")
                .replace(Regex("""\s*```$""", RegexOption.MULTILINE), "")
                .trim()
        }
        // Remove trailing commas before closing braces/brackets
        text = text.replace(Regex(""",\s*([\]}])"""), "$1")

        // Find array bounds
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart != -1 && arrayEnd > arrayStart) {
            return text.substring(arrayStart, arrayEnd + 1)
        }

        // Object with "steps" or "plan"
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        if (objStart != -1 && objEnd > objStart) {
            val candidate = text.substring(objStart, objEnd + 1)
            return try {
                val obj = JSONObject(candidate)
                when {
                    obj.has("steps") -> obj.getJSONArray("steps").toString()
                    obj.has("plan") -> obj.getJSONArray("plan").toString()
                    obj.has("toolName") || obj.has("tool") -> org.json.JSONArray().put(obj).toString()
                    else -> candidate
                }
            } catch (_: Exception) {
                candidate
            }
        }

        // If JSON array was truncated (missing closing ']'), salvage completed step objects
        if (arrayStart != -1) {
            val lastCloseBrace = text.lastIndexOf('}')
            if (lastCloseBrace > arrayStart) {
                val candidate = text.substring(arrayStart, lastCloseBrace + 1) + "\n]"
                try {
                    org.json.JSONArray(candidate)
                    return candidate
                } catch (_: Exception) {}
            }
        }

        return text.takeIf { it.isNotBlank() }
    }

    private fun buildSlmPlanningPrompt(goal: String): String {
        val toolNames = toolRegistry?.names().orEmpty()
        val toolList = if (toolNames.isEmpty()) {
            "web_search, workspace_write_file, workspace_shell, workspace_read_file, create_memory_file, invoke_subagent"
        } else {
            toolNames.joinToString(", ")
        }
        return buildString {
            appendLine("Decompose this user task into actionable tool steps.")
            appendLine("Allowed tools: [$toolList]")
            appendLine()
            appendLine("Respond ONLY with a JSON array:")
            appendLine("""[
  {
    "id": "step_1",
    "description": "Short description",
    "toolName": "tool_name",
    "arguments": {"param": "value"},
    "expectedOutcome": "Expected result"
  }
]""")
            appendLine("No markdown fences, no conversational prose, only the raw JSON array.")
            appendLine()
            appendLine("User Goal: $goal")
        }
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
            appendLine("You are an autonomous agent planner. Decompose the user goal into a DAG of actionable steps using the allowed tools.")
            appendLine("Allowed tools: [$toolList]")
            appendLine()
            appendLine("Respond with ONLY a valid JSON array of step objects, no conversational prose, no markdown fences.")
            appendLine("""[
  {
    "id": "step_1",
    "description": "Short description of the step",
    "toolName": "<tool name from allowed tools>",
    "arguments": {
      "path": "filename.html",
      "content": "concise initial content or template",
      "query": "search query keywords",
      "command": "sh command to run",
      "role": "Web Engineer",
      "goal": "autonomous subagent mission instructions"
    },
    "expectedOutcome": "Expected outcome of the step"
  }
]""")
            appendLine()
            appendLine("PLANNING GUIDELINES:")
            appendLine("- For research, facts, discoveries, or current information, start with 'web_search'.")
            appendLine("- For coding, building web pages, writing software, or creative workspace tasks, delegate to 'invoke_subagent' with a descriptive 'role' and comprehensive 'goal', OR write directly with 'workspace_write_file'.")
            appendLine("- Subagents launched via 'invoke_subagent' operate autonomously in the workspace with access to all tools (read/write/edit/shell) and automatically receive prior research findings.")
            appendLine("- If the user requests fact-checking, verification, or review, add an 'invoke_subagent' step with role 'Verification Reviewer'.")
            appendLine("- When writing files directly, specify 'path' and 'content' cleanly without unnecessary escape bloat.")
            appendLine("- Keep plans concise and effective (typically 1 to 4 steps).")
            appendLine()
            appendLine("""Example verification step:
  {"id": "step_2", "description": "Verify findings with an independent reviewer", "toolName": "invoke_subagent", "arguments": {"role": "Verification Reviewer", "goal": "Cross-check the research findings about <topic> using web_search and web_fetch; flag unsupported claims.", "max_steps": 8}, "expectedOutcome": "Findings verified"}""")
            appendLine()
            appendLine("User Goal: $goal")
        }
    }
}
