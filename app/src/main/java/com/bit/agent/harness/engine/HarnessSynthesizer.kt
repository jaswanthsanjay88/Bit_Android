package com.bit.agent.harness.engine

import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.state.TaskPlan
import com.bit.api.ChatMessage
import com.bit.api.LlmProviderResolver
import com.bit.api.Participant
import com.bit.api.ProviderConfig
import com.bit.api.StreamEvent
import com.bit.di.AppContainer
import com.bit.worker.ActiveModelSession
import com.bit.worker.LlmModelWorker
import org.json.JSONObject

/**
 * Final writer pass for the agent harness: turns raw step outputs (search results,
 * verification reports, corrections) into a polished markdown report for the end user.
 *
 * This is what separates "dumping tool output" from an Antigravity-style deliverable —
 * the user never sees tool mechanics, only synthesized knowledge. Falls back to null on
 * any failure so the engine can use its deterministic summary instead.
 * Provided via @Provides in HarnessModule (default-arg constructors break @Inject).
 */
class HarnessSynthesizer(
    private val logger: HarnessLogger = NoOpHarnessLogger
) {
    companion object {
        private const val TAG = "HarnessSynthesizer"
        private const val MAX_TOKENS = 2048
        private const val MAX_STEP_CHARS = 4000
        private const val MAX_TOTAL_CHARS = 24000
    }

    suspend fun synthesize(goal: String, plan: TaskPlan): String? {
        return try {
            val userContent = buildSynthesisInput(goal, plan)
            if (userContent.isBlank()) return null

            // 1. Local GGUF model if loaded
            if (LlmModelWorker.isGgufModelLoaded.value) {
                val fromLocal = runLocal(userContent)
                if (!fromLocal.isNullOrBlank()) {
                    logger.d(TAG, "Synthesis produced via local GGUF (${fromLocal.length} chars)")
                    return fromLocal
                }
            }

            // 2. Remote API provider
            val cfg = resolveInferenceConfig()
            if (cfg != null) {
                val fromRemote = runRemote(cfg, userContent)
                if (!fromRemote.isNullOrBlank()) {
                    logger.d(TAG, "Synthesis produced via remote API (${fromRemote.length} chars)")
                    return fromRemote
                }
            }
            null
        } catch (e: Exception) {
            logger.w(TAG, "Synthesis failed: ${e.message}")
            null
        }
    }

    private fun buildSynthesisPrompt(): String {
        return buildString {
            appendLine("You are writing the FINAL REPORT for a completed autonomous research task.")
            appendLine("You receive the user's goal and the raw outputs of every executed step (search results, verification reports, corrections). The user never sees those raw logs — your report IS the product.")
            appendLine()
            appendLine("Write a polished markdown report with EXACTLY this structure:")
            appendLine()
            appendLine("# <Descriptive Title>")
            appendLine()
            appendLine("## Key Findings")
            appendLine("Synthesized prose + bullets of what the research established. Rewrite everything in your own words — never paste raw tool output.")
            appendLine()
            appendLine("## Verification Audit")
            appendLine("(ONLY if reviewer/verification outputs are present) Per-reviewer summary with a claims table: | Claim | Status | Sources |. Statuses: VERIFIED / CONTRADICTED / UNCERTAIN. List any corrections that were applied.")
            appendLine()
            appendLine("## Convergence Matrix")
            appendLine("(ONLY if two or more reviewers ran) A table: | Dimension | Reviewer 1 | Reviewer 2 | Final Status | — then one line stating the final verdict (PASS / PASS_WITH_CONDITIONS / FAIL).")
            appendLine()
            appendLine("## Conclusion")
            appendLine("Short synthesis paragraph. Surface remaining uncertainties honestly.")
            appendLine()
            appendLine("RULES:")
            appendLine("- End-user tone: direct, knowledgeable, ZERO jargon about tools/steps/agents/subagents.")
            appendLine("- Markdown headers, bold, tables. LaTeX only where it earns its place.")
            appendLine("- If verification found contradictions or corrected claims, state them plainly.")
            appendLine("- 400-900 words unless the material demands more.")
        }.trimEnd()
    }

    private fun buildSynthesisInput(goal: String, plan: TaskPlan): String {
        return buildString {
            appendLine("USER GOAL:")
            appendLine(goal)
            appendLine()
            appendLine("EXECUTED STEP OUTPUTS:")
            var totalChars = 0
            plan.steps.forEachIndexed { i, step ->
                if (totalChars >= MAX_TOTAL_CHARS) {
                    appendLine("...[remaining step outputs truncated for length]")
                    return@forEachIndexed
                }
                val obs = step.observation
                val raw = obs?.payload?.takeIf { it.isNotBlank() }
                    ?: obs?.summary?.takeIf { it.isNotBlank() }
                    ?: "(no output)"
                val capped = if (raw.length > MAX_STEP_CHARS) {
                    raw.substring(0, MAX_STEP_CHARS) + "\n...[truncated]"
                } else raw
                totalChars += capped.length
                appendLine()
                appendLine("### Step ${i + 1} — ${step.description} [${step.toolName}]")
                appendLine(capped)
            }
        }.trimEnd()
    }

    private data class InferenceSetup(val provider: com.bit.api.LlmProvider, val config: ProviderConfig)

    private suspend fun resolveInferenceConfig(): InferenceSetup? {
        var modelId = ActiveModelSession.currentModelId.value
        if (modelId.isBlank()) {
            modelId = LlmModelWorker.currentGgufModelId.value ?: ""
        }
        if (modelId.isBlank()) return null

        val repoConfig = runCatching {
            AppContainer.getModelRepository().getConfigByModelId(modelId)
        }.getOrNull() ?: return null
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
            maxTokens = MAX_TOKENS,
            thinkingEnabled = false
        )
        return InferenceSetup(provider, config)
    }

    private suspend fun runLocal(userContent: String): String? {
        return try {
            val messages = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", buildSynthesisPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            }
            val builder = StringBuilder()
            LlmModelWorker.ggufGenerateMultiTurnStreaming(messages.toString(), maxTokens = MAX_TOKENS)
                .collect { event ->
                    if (event is com.bit.engine.GenerationEvent.Token) {
                        builder.append(event.text)
                    }
                }
            clean(builder.toString())
        } catch (e: Exception) {
            logger.w(TAG, "Local GGUF synthesis failed: ${e.message}")
            null
        }
    }

    private suspend fun runRemote(setup: InferenceSetup, userContent: String): String? {
        return try {
            val messages = listOf(
                ChatMessage(text = userContent, participant = Participant.USER)
            )
            val builder = StringBuilder()
            setup.provider.generateResponse(messages, setup.config).collect { event ->
                if (event is StreamEvent.TextChunk) builder.append(event.text)
            }
            clean(builder.toString())
        } catch (e: Exception) {
            logger.w(TAG, "Remote synthesis failed: ${e.message}")
            null
        }
    }

    /** Strips think-tags and stray code fences the model may wrap the report in. */
    private fun clean(raw: String): String {
        val stripped = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
            .trim()
        // Unwrap a single outer code fence if the model fenced the whole report
        val fenced = Regex("^```(?:markdown|md)?\\s*\\n([\\s\\S]*?)\\n```\\s*$").find(stripped)
        return (fenced?.groupValues?.get(1) ?: stripped).ifBlank { "" }
    }
}
