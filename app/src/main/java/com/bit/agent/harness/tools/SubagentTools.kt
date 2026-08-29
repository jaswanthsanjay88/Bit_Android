package com.bit.agent.harness.tools

import com.bit.agent.harness.model.SubagentResult
import com.bit.agent.harness.model.SubagentTask
import com.bit.agent.harness.model.ToolObservation
import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Interface allowing subagent tasks to be executed by the Harness engine or subagent runner.
 * [executeAll] defaults to sequential; implementations may override for parallel fan-out.
 */
fun interface SubagentExecutor {
    suspend fun execute(task: SubagentTask): SubagentResult

    suspend fun executeAll(tasks: List<SubagentTask>): List<SubagentResult> =
        tasks.map { execute(it) }
}

/**
 * Tool allowing the main agent to deploy specialized subagents concurrently or in sequence.
 */
class InvokeSubagentTool(
    private val subagentExecutor: SubagentExecutor
) : AgentTool {
    companion object {
        const val NAME = "invoke_subagent"
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = """
                Deploy specialized autonomous subagent(s) to perform focused tasks (e.g. Code Reviewer, Security Scanner, Research, Verification Reviewer).
                Single mode: provide 'role' + 'goal'. Parallel fan-out mode: provide 'tasks' as an array of {role, goal, max_steps} — up to 3 tasks run CONCURRENTLY and results are combined; use only for independent tasks.
                Subagents execute in isolated contexts and report back a concise synthesized summary, artifacts, and optional structured claims.
            """.trimIndent(),
            parameters = ToolParameters(
                properties = mapOf(
                    "role" to ToolProperty(
                        type = "string",
                        description = "Specialized role/title for the subagent (single mode). Ignored when 'tasks' is provided."
                    ),
                    "goal" to ToolProperty(
                        type = "string",
                        description = "Clear, actionable task description for the subagent (single mode). Ignored when 'tasks' is provided."
                    ),
                    "tasks" to ToolProperty(
                        type = "array",
                        description = "Optional parallel fan-out: array of {\"role\": string, \"goal\": string, \"max_steps\": int}. Max 3; tasks must be independent of each other."
                    ),
                    "max_steps" to ToolProperty(
                        type = "integer",
                        description = "Optional max step budget for a single subagent (default: 8)"
                    )
                ),
                required = listOf("role", "goal")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)

            // Parallel fan-out mode: "tasks": [{"role","goal","max_steps"?}, ...]
            val tasksArr = args.optJSONArray("tasks")
            if (tasksArr != null && tasksArr.length() > 0) {
                val tasks = (0 until tasksArr.length()).mapNotNull { i ->
                    val o = tasksArr.optJSONObject(i) ?: return@mapNotNull null
                    val g = o.optString("goal").trim()
                    if (g.isBlank()) null
                    else SubagentTask(
                        id = UUID.randomUUID().toString(),
                        role = o.optString("role", "Specialist").trim(),
                        goal = g,
                        maxSteps = o.optInt("max_steps", 8).coerceIn(1, 20)
                    )
                }
                if (tasks.isEmpty()) {
                    return ToolObservation.error(
                        summary = "invoke_subagent fan-out had no usable tasks (every entry missing a goal).",
                        recoveryHint = "Provide a 'goal' for each entry in the tasks array.",
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                }
                val results = subagentExecutor.executeAll(tasks)
                val combinedOutput = results.joinToString("\n\n---\n\n") { r ->
                    "## Subagent [${r.role}]" + (if (r.isSuccess) "" else " (incomplete)") + "\n${r.output}"
                }
                val allArtifacts = results.flatMap { it.artifacts }.distinct()
                val allClaims = results.flatMap { it.claims }
                val successCount = results.count { it.isSuccess }
                val payloadJson = JSONObject().apply {
                    put("mode", "fan_out")
                    put("total", results.size)
                    put("succeeded", successCount)
                    put("combined_output", combinedOutput)
                    put("claims", JSONArray(allClaims.map { claim ->
                        JSONObject().apply {
                            put("claim", claim.claim)
                            put("status", claim.status)
                            put("sources", JSONArray(claim.sources))
                            put("notes", claim.notes)
                        }
                    }))
                    put("results", JSONArray(results.map { r ->
                        JSONObject().apply {
                            put("role", r.role)
                            put("is_success", r.isSuccess)
                            put("summary", r.summary)
                            put("output", r.output)
                            put("steps_completed", r.stepsCompleted)
                        }
                    }))
                }
                return if (successCount == results.size) {
                    ToolObservation.success(
                        summary = "Fan-out complete: ${successCount}/${results.size} subagents succeeded.",
                        payload = payloadJson.toString(),
                        artifacts = allArtifacts,
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                } else {
                    ToolObservation.warning(
                        summary = "Fan-out partial: ${successCount}/${results.size} subagents succeeded.",
                        payload = payloadJson.toString(),
                        recoveryHint = "Review failed subagent outputs; retry failed branches with narrowed scope.",
                        artifacts = allArtifacts,
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                }
            }

            // Single-task mode
            val role = args.optString("role", "Specialist Agent").trim()
            val goal = args.optString("goal", "").trim()
            val maxSteps = args.optInt("max_steps", 8).coerceIn(1, 20)

            if (goal.isBlank()) {
                return ToolObservation.error("Subagent goal cannot be blank", "Provide a clear task goal.")
            }

            val task = SubagentTask(
                id = UUID.randomUUID().toString(),
                role = role,
                goal = goal,
                maxSteps = maxSteps
            )

            val result = subagentExecutor.execute(task)

            val payloadJson = JSONObject().apply {
                put("subagent_id", result.taskId)
                put("role", result.role)
                put("is_success", result.isSuccess)
                put("summary", result.summary)
                put("output", result.output)
                put("artifacts", JSONArray(result.artifacts))
                put("steps_completed", result.stepsCompleted)
                if (result.claims.isNotEmpty()) {
                    put("claims", JSONArray(result.claims.map { claim ->
                        JSONObject().apply {
                            put("claim", claim.claim)
                            put("status", claim.status)
                            put("sources", JSONArray(claim.sources))
                            put("notes", claim.notes)
                        }
                    }))
                }
            }

            if (result.isSuccess) {
                ToolObservation.success(
                    summary = "Subagent [$role] completed: ${result.summary}",
                    payload = payloadJson.toString(),
                    artifacts = result.artifacts,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            } else {
                ToolObservation.warning(
                    summary = "Subagent [$role] finished with warning: ${result.summary}",
                    payload = payloadJson.toString(),
                    recoveryHint = "Review subagent output and adjust downstream steps.",
                    artifacts = result.artifacts,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Subagent deployment failed: ${e.message}",
                recoveryHint = "Retry subagent task with narrowed scope.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

/**
 * DeepSeek Harness Ralph Loop Tool.
 * Runs fresh-agent rounds toward an immutable objective with bounded structured handoffs between rounds.
 */
class RalphTool(
    private val subagentExecutor: SubagentExecutor
) : AgentTool {
    companion object {
        const val NAME = "ralph"
        const val DEFAULT_MAX_ROUNDS = 5
    }

    override val promptOrder: Int = 120
    override val systemPromptContribution: String = """
        Use 'ralph' ONLY when fresh-agent iterative loop execution is requested. Each round opens a fresh child context with bounded structured handoffs.
    """.trimIndent()

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = """
                Run a foreground fresh-agent Ralph loop toward one immutable objective.
                Each round starts a fresh child agent with no parent conversation; the shared workspace is long-term memory, and only a bounded structured report crosses rounds.
                Round report statuses: 'continue' (work remains), 'complete' (objective satisfied), 'blocked' (requires human intervention).
            """.trimIndent(),
            parameters = ToolParameters(
                properties = mapOf(
                    "objective" to ToolProperty(
                        type = "string",
                        description = "The immutable completion objective for every fresh Ralph round."
                    ),
                    "max_rounds" to ToolProperty(
                        type = "integer",
                        description = "Optional round limit (default: 5, max: 256)."
                    )
                ),
                required = listOf("objective")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val objective = args.optString("objective", "").trim()
            if (objective.isEmpty()) {
                return ToolObservation.error("Ralph objective cannot be empty", "Provide a clear immutable objective.")
            }
            val maxRounds = args.optInt("max_rounds", DEFAULT_MAX_ROUNDS).coerceIn(1, 256)

            var previousHandoff = "(none — this is the first round)"
            var roundsStarted = 0
            val accumulatedArtifacts = mutableListOf<String>()
            var lastReportObj: JSONObject? = null

            for (round in 1..maxRounds) {
                roundsStarted = round
                val roundPrompt = buildString {
                    appendLine("You are one fresh worker in a foreground Ralph loop.")
                    appendLine("Immutable objective:\n$objective")
                    appendLine("Ralph round: $round of $maxRounds.")
                    appendLine("The shared workspace and filesystem are the long-term memory and source of truth.")
                    appendLine("Previous structured handoff:\n$previousHandoff")
                    appendLine()
                    appendLine("Perform in-scope concrete work, verify changes, and return a structured report JSON:")
                    appendLine("{\"status\": \"continue\"|\"complete\"|\"blocked\", \"summary\": \"...\", \"evidence\": [\"...\"], \"nextSteps\": [\"...\"], \"blocker\": \"...\"}")
                }

                val task = SubagentTask(
                    id = UUID.randomUUID().toString(),
                    role = "Ralph Worker Round $round",
                    goal = roundPrompt,
                    maxSteps = 8
                )

                val result = subagentExecutor.execute(task)
                accumulatedArtifacts.addAll(result.artifacts)

                val report = parseReport(result.output, result.summary)
                lastReportObj = report

                val status = report.optString("status", "continue").lowercase()
                val summary = report.optString("summary", result.summary)
                val blocker = report.optString("blocker", "")

                previousHandoff = report.toString()

                if (status == "complete") {
                    val payloadJson = JSONObject().apply {
                        put("status", "complete")
                        put("rounds_completed", round)
                        put("objective", objective)
                        put("final_report", report)
                    }
                    return ToolObservation.success(
                        summary = "Ralph worker completed objective after $round round${if (round == 1) "" else "s"}: $summary",
                        payload = payloadJson.toString(),
                        artifacts = accumulatedArtifacts.distinct(),
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                } else if (status == "blocked") {
                    val payloadJson = JSONObject().apply {
                        put("status", "blocked")
                        put("rounds_started", round)
                        put("objective", objective)
                        put("blocker", blocker)
                        put("report", report)
                    }
                    return ToolObservation.warning(
                        summary = "Ralph worker reported a blocker at round $round: $blocker",
                        payload = payloadJson.toString(),
                        recoveryHint = "Resolve blocker or provide required human inputs.",
                        artifacts = accumulatedArtifacts.distinct(),
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                }
            }

            val payloadJson = JSONObject().apply {
                put("status", "budget_limited")
                put("rounds_started", maxRounds)
                put("objective", objective)
                put("last_report", lastReportObj ?: JSONObject())
            }
            ToolObservation.warning(
                summary = "Ralph reached round limit ($maxRounds rounds) with work remaining.",
                payload = payloadJson.toString(),
                recoveryHint = "Review last report nextSteps and continue or increase max_rounds.",
                artifacts = accumulatedArtifacts.distinct(),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Ralph loop failed: ${e.message}",
                recoveryHint = "Retry Ralph loop with adjusted objective.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun parseReport(output: String, fallbackSummary: String): JSONObject {
        return try {
            val jsonStart = output.indexOf('{')
            val jsonEnd = output.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val candidate = output.substring(jsonStart, jsonEnd + 1)
                JSONObject(candidate)
            } else {
                buildFallbackReport(output, fallbackSummary)
            }
        } catch (_: Exception) {
            buildFallbackReport(output, fallbackSummary)
        }
    }

    private fun buildFallbackReport(output: String, fallbackSummary: String): JSONObject {
        val lower = output.lowercase()
        val isComplete = lower.contains("objective complete") || lower.contains("goal achieved") || lower.contains("task finished")
        val isBlocked = lower.contains("blocked:") || lower.contains("cannot proceed") || lower.contains("error:")

        return JSONObject().apply {
            put("status", if (isComplete) "complete" else if (isBlocked) "blocked" else "continue")
            put("summary", fallbackSummary.ifBlank { output.take(200) })
            put("evidence", org.json.JSONArray(listOf(output.take(500))))
            put("nextSteps", org.json.JSONArray(if (isComplete) emptyList<String>() else listOf("Continue execution in next round")))
            put("blocker", if (isBlocked) output.take(200) else "")
        }
    }
}
