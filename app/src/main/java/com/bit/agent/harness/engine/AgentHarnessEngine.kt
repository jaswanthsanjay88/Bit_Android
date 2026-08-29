package com.bit.agent.harness.engine

import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.gate.GateResult
import com.bit.agent.harness.gate.SelfCorrectionPlanner
import com.bit.agent.harness.gate.StepGateChecker
import com.bit.agent.harness.model.SubagentTask
import com.bit.agent.harness.model.ToolApprovalState
import com.bit.agent.harness.model.ToolObservation
import com.bit.agent.harness.state.AgentHarnessState
import com.bit.agent.harness.state.StepStatus
import com.bit.agent.harness.state.TaskPlan
import com.bit.agent.harness.state.TaskStep
import com.bit.agent.harness.tools.AgentToolBridge
import com.bit.agent.harness.tools.AgentToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Autonomous Finite State Machine (DAG) Engine for On-Device Multi-Agent Task Execution.
 * Supports up to 256 steps, interactive tool approval gating, subagent deployments,
 * and self-correction recovery loops.
 */
@Singleton
class AgentHarnessEngine @Inject constructor(
    @ApplicationContext private val context: Context? = null,
    private val toolBridge: AgentToolBridge,
    private val toolRegistry: AgentToolRegistry? = null,
    private val gateChecker: StepGateChecker,
    private val correctionPlanner: SelfCorrectionPlanner,
    private val logger: HarnessLogger = NoOpHarnessLogger,
    private val planGenerator: PlanGenerator? = null,
    private val synthesizer: HarnessSynthesizer? = null
) {
    companion object {
        private const val TAG = "AgentHarnessEngine"
        const val DEFAULT_MAX_STEPS = 256
        const val DEFAULT_MAX_RETRIES_PER_STEP = 3
        private const val APPROVAL_TIMEOUT_MS = 5L * 60L * 1000L
        private const val MAX_SUBAGENT_CONTEXT_CHARS = 6000
    }

    val sessionLog: com.bit.agent.harness.model.HarnessSessionLog = com.bit.agent.harness.model.HarnessSessionLog()
    val toolPipeline: com.bit.agent.harness.tools.AgentToolPipeline = com.bit.agent.harness.tools.AgentToolPipeline(logger)
    val promptAssembler: SystemPromptAssembler? = toolRegistry?.let { SystemPromptAssembler(it) }

    fun getSystemPrompt(baseRolePrompt: String = ""): String =
        promptAssembler?.assemblePrompt(baseRolePrompt) ?: baseRolePrompt

    private val _state = MutableStateFlow<AgentHarnessState>(AgentHarnessState.Idle)
    val state = _state.asStateFlow()

    /** Pending human-in-the-loop approvals keyed by step id. */
    private val pendingApprovals =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    fun reset() {
        completeAllPendingOnReset()
        sessionLog.clear()
        _state.value = AgentHarnessState.Idle
    }

    /** Resolves a pending approval as granted (UI calls this when user taps Approve). */
    fun approveStep(stepId: String) {
        pendingApprovals.remove(stepId)?.complete(true)
            ?: logger.w(TAG, "approveStep: no pending approval for step '$stepId'")
    }

    /** Resolves a pending approval as denied (UI calls this when user taps Deny). */
    fun denyStep(stepId: String, reason: String = "") {
        pendingApprovals.remove(stepId)?.complete(false)
            ?: logger.w(TAG, "denyStep: no pending approval for step '$stepId'")
    }

    /** Pending ask_user questions keyed by step id. */
    private val pendingAnswers =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String>>()

    /**
     * Delivers the user's typed answer to a pending ask_user step.
     * Empty string is treated as "no response".
     */
    fun answerPendingQuestion(stepId: String, answer: String) {
        pendingAnswers.remove(stepId)?.complete(answer.trim())
            ?: logger.w(TAG, "answerPendingQuestion: no pending question for step '$stepId'")
    }

    private fun completeAllPendingOnReset() {
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        pendingAnswers.values.forEach { it.complete("") }
        pendingAnswers.clear()
    }

    /**
     * Executes a goal through the bounded state machine DAG.
     */
    fun executeGoal(
        goal: String,
        explicitPlan: TaskPlan? = null,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        maxRetriesPerStep: Int = DEFAULT_MAX_RETRIES_PER_STEP
    ): Flow<AgentHarnessState> = flow {
        val startTime = System.currentTimeMillis()
        val turnId = java.util.UUID.randomUUID().toString()
        sessionLog.append(com.bit.agent.harness.model.HarnessSessionEvent.TurnStart(turnId = turnId, goal = goal))

        var turnCount = 0
        val accumulatedArtifacts = mutableListOf<String>()
        // Santa-Method guard: each reviewer step may trigger revision injection at most once.
        val injectedRevisionSteps = mutableSetOf<String>()

        emitState(AgentHarnessState.Decomposing(goal, attempt = 1))

        val plan = explicitPlan ?: generatePlan(goal, maxSteps)
        if (plan.steps.isEmpty()) {
            val failState = AgentHarnessState.Failed("Could not decompose goal into executable steps.")
            sessionLog.append(com.bit.agent.harness.model.HarnessSessionEvent.TurnEnd(turnId = turnId, finalResult = failState.reason, success = false))
            emitState(failState)
            return@flow
        }

        // Indexed while (not for/withIndex) so convergence steps can be appended mid-run.
        var index = 0
        while (index < plan.steps.size) {
            val step = plan.steps[index]
            var stepResolved = false

            while (!stepResolved) {
                if (turnCount >= maxSteps) {
                    step.status = StepStatus.FAILED
                    val failState = AgentHarnessState.Failed(
                        reason = "Global step budget of $maxSteps exhausted before step '${step.id}' completed.",
                        step = step,
                        partialArtifacts = accumulatedArtifacts.distinct()
                    )
                    emitState(failState)
                    return@flow
                }
                turnCount++
                step.status = StepStatus.RUNNING

                // Check for subagent tool
                if (step.toolName == "invoke_subagent") {
                    // Subagents run in an isolated context — pipe prior step findings into their
                    // goal(s) by REWRITING step.toolArguments. The registry tool executes from raw
                    // args, so mutating arguments (not just the state) is what delivers context.
                    val argsObj = try {
                        JSONObject(step.toolArguments)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    val tasksArr = argsObj.optJSONArray("tasks")
                    if (tasksArr != null && tasksArr.length() > 0) {
                        // Parallel fan-out: every task goal receives the piped findings
                        for (i in 0 until tasksArr.length()) {
                            val t = tasksArr.optJSONObject(i) ?: continue
                            val g = t.optString("goal").ifBlank { step.description }
                            t.put("goal", buildSubagentGoal(g, plan, index))
                        }
                        step.toolArguments = argsObj.toString()
                        val roles = (0 until tasksArr.length())
                            .mapNotNull { tasksArr.optJSONObject(it)?.optString("role") }
                            .joinToString("/")
                        emitState(
                            AgentHarnessState.SubagentRunning(
                                subagentTask = SubagentTask(
                                    id = step.id,
                                    role = "Fan-out ×${tasksArr.length()} [$roles]".take(90),
                                    goal = "Independent parallel subagent tasks",
                                    maxSteps = 8
                                ),
                                parentStepIndex = index + 1,
                                totalParentSteps = plan.steps.size
                            )
                        )
                    } else {
                        val baseGoal = argsObj.optString("goal", step.description).ifBlank { step.description }
                        argsObj.put("goal", buildSubagentGoal(baseGoal, plan, index))
                        step.toolArguments = argsObj.toString()
                        emitState(
                            AgentHarnessState.SubagentRunning(
                                subagentTask = SubagentTask(
                                    id = step.id,
                                    role = argsObj.optString("role", "Specialist Agent"),
                                    goal = argsObj.optString("goal"),
                                    maxSteps = argsObj.optInt("max_steps", 8)
                                ),
                                parentStepIndex = index + 1,
                                totalParentSteps = plan.steps.size
                            )
                        )
                    }
                } else {
                    emitState(
                        AgentHarnessState.Executing(
                            activeStep = step,
                            toolName = step.toolName,
                            stepIndex = index + 1,
                            totalSteps = plan.steps.size
                        )
                    )
                }

                // Dynamic DAG data pipelining: pipe prior step output if writing file or memory
                if (index > 0 && (step.toolName == "workspace_write_file" || step.toolName == "create_memory_file" || step.toolName == "create_memory")) {
                    val priorOutputs = plan.steps.take(index).mapNotNull { prior ->
                        val obs = prior.observation
                        val data = obs?.payload?.takeIf { it.isNotBlank() } ?: obs?.summary?.takeIf { it.isNotBlank() }
                        if (data != null && prior.toolName != "workspace_write_file") {
                            prior to data
                        } else null
                    }
                    val latestPrior = priorOutputs.lastOrNull()
                    if (latestPrior != null) {
                        val (_, rawPrevData) = latestPrior
                        val prevData = if (com.bit.util.SearchResultFormatter.isRawSearchResult(rawPrevData)) {
                            com.bit.util.SearchResultFormatter.format(rawPrevData)
                        } else rawPrevData
                        try {
                            val args = JSONObject(step.toolArguments)
                            val currText = args.optString("text", "")
                            if (currText.isBlank() || currText.startsWith("Summary for:") || currText.length < 40) {
                                args.put("text", prevData)
                                step.toolArguments = args.toString()
                            }
                            val currContent = args.optString("content", "")
                            if (currContent.isBlank() || currContent.startsWith("Summary for:") || currContent == goal || currContent.length < 40) {
                                args.put("content", prevData)
                                step.toolArguments = args.toString()
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Check tool approval requirement — genuinely suspend until the user decides.
                val registeredTool = toolRegistry?.get(step.toolName)
                val isUserQuestion = step.toolName.equals("ask_user", ignoreCase = true)
                val requiresApproval = registeredTool?.needsApproval(step.toolArguments) == true &&
                        step.observation?.approvalState !is ToolApprovalState.Approved

                var observation: ToolObservation = when {
                    // ask_user suspends until the user types an answer in the chat UI
                    isUserQuestion -> {
                        val question = try {
                            JSONObject(step.toolArguments).optString("question")
                                .ifBlank { step.description }
                        } catch (_: Exception) {
                            step.description
                        }
                        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                        pendingAnswers[step.id] = deferred
                        emitState(
                            AgentHarnessState.AwaitingApproval(
                                activeStep = step,
                                toolName = "ask_user",
                                toolArguments = JSONObject(mapOf("question" to question)).toString(),
                                approvalState = ToolApprovalState.Pending
                            )
                        )
                        val answer = kotlinx.coroutines.withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                            deferred.await()
                        } ?: ""
                        if (answer.isNotBlank()) {
                            ToolObservation.success(
                                summary = "User answered: $answer",
                                payload = answer
                            ).copy(approvalState = ToolApprovalState.Answered(answer))
                        } else {
                            ToolObservation.error(
                                summary = "The user did not respond in time.",
                                recoveryHint = "Proceed with the most reasonable default assumption and state it explicitly."
                            ).copy(approvalState = ToolApprovalState.Denied("timeout"))
                        }
                    }

                    requiresApproval -> {
                        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        pendingApprovals[step.id] = deferred
                        emitState(
                            AgentHarnessState.AwaitingApproval(
                                activeStep = step,
                                toolName = step.toolName,
                                toolArguments = step.toolArguments,
                                approvalState = ToolApprovalState.Pending
                            )
                        )
                        val approved = kotlinx.coroutines.withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                            deferred.await()
                        } ?: false
                        when {
                            approved -> registeredTool.execute(step.toolArguments).let { obs ->
                                obs.copy(approvalState = ToolApprovalState.Approved)
                            }
                            else -> ToolObservation.error(
                                summary = "Execution of '${step.toolName}' was denied by the user" +
                                        (if (deferred.isCancelled) " (approval timed out)." else "."),
                                recoveryHint = "Revise the plan to avoid restricted actions, or ask the user for guidance."
                            ).copy(approvalState = ToolApprovalState.Denied("user"))
                        }
                    }

                    registeredTool != null -> toolPipeline.execute(registeredTool, step.toolArguments, context, step.id)

                    else -> toolBridge.execute(step.toolName, step.toolArguments)
                }

                if (context != null && registeredTool == null) {
                    observation = com.bit.agent.harness.util.ToolOutputTruncator.maybeTruncateObservation(
                        context = context,
                        toolCallId = step.id,
                        observation = observation,
                        hasShellAccess = toolRegistry?.get("workspace_shell") != null
                    )
                }

                // Subagents return a JSON envelope; downstream consumers (data pipelining,
                // synthesis) should see the clean synthesized output text instead.
                if (step.toolName.equals("invoke_subagent", ignoreCase = true)) {
                    try {
                        val env = JSONObject(observation.payload ?: "{}")
                        val cleanOut = env.optString("combined_output").takeIf { it.isNotBlank() }
                            ?: env.optString("output").takeIf { it.isNotBlank() }
                            ?: env.optString("summary").takeIf { it.isNotBlank() }
                        if (!cleanOut.isNullOrBlank()) {
                            observation = observation.copy(payload = cleanOut)
                        }
                    } catch (_: Exception) {}
                }

                step.observation = observation
                accumulatedArtifacts.addAll(observation.artifacts)

                sessionLog.append(
                    com.bit.agent.harness.model.HarnessSessionEvent.StepStart(
                        turnId = turnId,
                        stepId = step.id,
                        toolName = step.toolName,
                        arguments = step.toolArguments
                    )
                )
                sessionLog.append(
                    com.bit.agent.harness.model.HarnessSessionEvent.ToolResultRecorded(
                        turnId = turnId,
                        stepId = step.id,
                        toolName = step.toolName,
                        observation = observation
                    )
                )

                // Gate Check
                val gateResult: GateResult = gateChecker.verify(step, observation)

                sessionLog.append(
                    com.bit.agent.harness.model.HarnessSessionEvent.StepEnd(
                        turnId = turnId,
                        stepId = step.id,
                        status = if (gateResult.passed) "PASSED" else "FAILED"
                    )
                )

                emitState(
                    AgentHarnessState.GateChecking(
                        step = step,
                        observation = observation,
                        passed = gateResult.passed
                    )
                )

                if (gateResult.passed) {
                    step.status = StepStatus.PASSED
                    stepResolved = true
                } else {
                    // Self-Correction Loop
                    if (step.retryCount < maxRetriesPerStep) {
                        step.retryCount++
                        val planCorrection = correctionPlanner.planCorrection(step, gateResult, observation)

                        emitState(
                            AgentHarnessState.SelfCorrecting(
                                failedStep = step,
                                rootCause = gateResult.reason,
                                recoveryHint = gateResult.recoveryHint ?: observation.recoveryHint ?: "Retrying with adjusted input",
                                retryCount = step.retryCount,
                                maxRetries = maxRetriesPerStep
                            )
                        )

                        step.toolArguments = planCorrection.revisedArguments
                        planCorrection.alternativeToolName?.let { altTool ->
                            logger.d(TAG, "Step '${step.id}' switching tool ${step.toolName} -> $altTool after retry ${step.retryCount}")
                            step.toolName = altTool
                        }
                    } else {
                        step.status = StepStatus.FAILED
                        val failState = AgentHarnessState.Failed(
                            reason = "Step '${step.id}' failed validation after ${step.retryCount} attempts: ${gateResult.reason}",
                            step = step,
                            partialArtifacts = accumulatedArtifacts.distinct()
                        )
                        emitState(failState)
                        return@flow
                    }
                }
            }

            // ── Santa-Method convergence ──
            // If a reviewer subagent flagged corrections (or issued a failing verdict),
            // inject a revision step + a convergence re-check right after it — once per
            // reviewer step, and never for injected steps themselves.
            if (
                step.status == StepStatus.PASSED &&
                step.toolName.equals("invoke_subagent", ignoreCase = true) &&
                !step.id.endsWith("_revise") &&
                !step.id.endsWith("_converge") &&
                step.id !in injectedRevisionSteps &&
                reviewerDemandsCorrections(step.observation)
            ) {
                injectedRevisionSteps.add(step.id)
                if (turnCount + 2 < maxSteps) {
                    val revisionStep = TaskStep(
                        id = "${step.id}_revise",
                        description = "Apply reviewer corrections and produce the revised findings",
                        toolName = "invoke_subagent",
                        toolArguments = JSONObject(
                            mapOf(
                                "role" to "Research Editor",
                                "goal" to "You receive the original research findings plus independent reviewer " +
                                        "audits (piped below). Apply EVERY item marked CORRECTION REQUIRED or " +
                                        "ADDITION REQUIRED, keep verified content unchanged, and output the FULL " +
                                        "revised findings. End with a summary of changes applied.",
                                "max_steps" to 8
                            )
                        ).toString(),
                        expectedOutcome = "Revised findings incorporating all reviewer corrections"
                    )
                    val convergenceStep = TaskStep(
                        id = "${step.id}_converge",
                        description = "Final convergence check on the revised findings",
                        toolName = "invoke_subagent",
                        toolArguments = JSONObject(
                            mapOf(
                                "role" to "Audit Convergence Checker",
                                "goal" to "Convergence audit: confirm the revised findings address every reviewer " +
                                        "correction and add no unsupported claims. Final verdict must be PASS or " +
                                        "PASS_WITH_CONDITIONS. If FAIL, list the remaining issues explicitly.",
                                "max_steps" to 6
                            )
                        ).toString(),
                        expectedOutcome = "Converged audit verdict (PASS / PASS WITH CONDITIONS)"
                    )
                    plan.steps.addAll(index + 1, listOf(revisionStep, convergenceStep))
                    logger.d(TAG, "Reviewer flagged corrections — injected revision + convergence steps after '${step.id}'")
                } else {
                    logger.w(TAG, "Reviewer flagged corrections but step budget cannot fit revision; skipping injection")
                }
            }

            index++
        }

        val totalTime = System.currentTimeMillis() - startTime
        // Final writer pass: LLM-synthesized report for the end user; deterministic
        // step summary only as fallback when synthesis is unavailable or fails.
        val finalResult = synthesizer?.synthesize(goal, plan)?.takeIf { it.isNotBlank() }
            ?: synthesizeResults(goal, plan)
        val completedState = AgentHarnessState.Completed(
            finalResult = finalResult,
            artifacts = accumulatedArtifacts.distinct(),
            totalTurns = turnCount,
            executionTimeMs = totalTime
        )
        sessionLog.append(
            com.bit.agent.harness.model.HarnessSessionEvent.TurnEnd(
                turnId = turnId,
                finalResult = finalResult,
                success = true
            )
        )
        emitState(completedState)
    }.flowOn(Dispatchers.Default)

    private suspend fun FlowCollector<AgentHarnessState>.emitState(newState: AgentHarnessState) {
        _state.value = newState
        emit(newState)
    }

    private suspend fun generatePlan(goal: String, maxSteps: Int): TaskPlan {
        if (planGenerator != null) {
            try {
                val raw = planGenerator.generatePlanJson(goal)
                if (!raw.isNullOrBlank()) {
                    val llmPlan = parsePlanJson(goal, raw)
                    if (llmPlan.steps.isNotEmpty()) {
                        logger.d(TAG, "LLM produced a ${llmPlan.steps.size}-step plan for goal: $goal")
                        return llmPlan.copy(steps = llmPlan.steps.take(maxSteps).toMutableList())
                    }
                }
                logger.w(TAG, "LLM planner returned an unusable plan; using heuristic decomposition.")
            } catch (e: Exception) {
                logger.w(TAG, "LLM planning threw; using heuristic decomposition: ${e.message}")
            }
        }
        return decomposeGoal(goal, maxSteps)
    }

    fun parsePlanJson(goal: String, jsonString: String): TaskPlan {
        val steps = mutableListOf<TaskStep>()
        try {
            var text = jsonString.trim()
            if (text.startsWith("```")) {
                text = text.substringAfter("\n").substringBeforeLast("```").trim()
            }
            val jsonArrayString = if (text.contains("[") && text.contains("]")) {
                "[" + text.substringAfter("[").substringBeforeLast("]") + "]"
            } else text

            val array = if (jsonArrayString.trim().startsWith("[")) {
                JSONArray(jsonArrayString.trim())
            } else {
                val obj = JSONObject(text)
                obj.optJSONArray("steps") ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = item.optString("id", "step_${i + 1}")
                val desc = item.optString("description", "Step ${i + 1}")
                val tool = item.optString("toolName", item.optString("tool", "workspace_shell"))
                val args = if (item.has("toolArguments")) {
                    val a = item.get("toolArguments")
                    if (a is JSONObject) a.toString() else a.toString()
                } else if (item.has("arguments")) {
                    val a = item.get("arguments")
                    if (a is JSONObject) a.toString() else a.toString()
                } else "{}"
                val expected = item.optString("expectedOutcome", item.optString("expected", "Success"))

                steps.add(
                    TaskStep(
                        id = id,
                        description = desc,
                        toolName = tool,
                        toolArguments = args,
                        expectedOutcome = expected
                    )
                )
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to parse plan JSON: ${e.message}")
        }
        return TaskPlan(goal = goal, steps = steps)
    }

    fun decomposeToDagPlan(
        goal: String,
        enabledTools: Set<String> = emptySet(),
        maxSteps: Int = DEFAULT_MAX_STEPS
    ): TaskPlan {
        return decomposeGoal(goal, maxSteps)
    }

    fun formatPlanToMarkdown(plan: TaskPlan, activeStepIndex: Int? = null): String {
        if (plan.steps.isEmpty()) return ""
        return buildString {
            appendLine("### Execution Plan (DAG)")
            plan.steps.forEachIndexed { idx, step ->
                val stepNum = idx + 1
                val isCurrent = activeStepIndex == stepNum
                val marker = when {
                    step.status == StepStatus.PASSED -> "[x]"
                    step.status == StepStatus.RUNNING || isCurrent -> "[RUNNING]"
                    step.status == StepStatus.FAILED -> "[!]"
                    step.retryCount > 0 -> "[RETRY]"
                    else -> "[ ]"
                }
                appendLine("- $marker **$stepNum. ${step.description}** (`${step.toolName}`)")
            }
        }.trimEnd()
    }

    fun decomposeGoal(goal: String, maxSteps: Int): TaskPlan {
        val lower = goal.lowercase()
        val steps = mutableListOf<TaskStep>()

        val filenameRegex = Regex("""([a-zA-Z0-9_\-./]+\.(?:md|py|txt|json|kt|sh|js|html|cpp|rs|go))""", RegexOption.IGNORE_CASE)
        val extractedFilename = filenameRegex.find(goal)?.groupValues?.get(1)?.trim()
            ?: (if (lower.contains("kotlin")) "kotlin.md" else "search_summary.md")

        // Clean, focused search query: first actionable clause only — never the raw
        // multi-instruction goal text (which pollutes search engines).
        val searchQuery = extractSearchQuery(goal)

        // Multi-part goals like "research X then have a reviewer verify" must not lose
        // the verification phase when the LLM planner is unavailable.
        val wantsVerification = Regex(
            """\b(reviewer?|verif\w*|fact.?\s?check\w*|double.?\s?check\w*|cross.?\s?check\w*|audit|validate)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(goal)

        // Plural-reviewer goals ("two independent reviewers: one checks X, one checks Y")
        // fan out as a single parallel invoke_subagent step with a tasks[] array.
        val multiReviewer = Regex(
            """\b(two|2|both|multiple|several)\b[^.;]{0,40}\b(reviewers?|verifiers?|agents?)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(goal) ||
                Regex(
                    """one\b[^.;]{0,60}\b(checks?|verifies|validates|reviews?)\b[^.;]{0,120}\b(other|second|one)\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(goal)

        val focusRegex = Regex(
            """one\b[^.;]{0,20}\b(?:checks?|verifies|validates|reviews?)\b\s+(.+?)\s*[;,]?\s+""" +
                    """(?:and\s+)?(?:the\s+)?(?:other|second|one)\b[^.;]{0,20}\b(?:checks?|verifies|validates|reviews?)\b\s+(.+?)\s*(?:[.;:!?]|$)""",
            RegexOption.IGNORE_CASE
        )
        val focusMatch = runCatching { focusRegex.find(goal) }.getOrNull()
        val focusValues = focusMatch?.groupValues ?: emptyList()
        val focusA = focusValues.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() }
            ?: "technical accuracy of the claims"
        val focusB = focusValues.getOrNull(2)?.trim().takeUnless { it.isNullOrBlank() }
            ?: "commercial and business claims"

        // Research Reader: fetches full page content (web_fetch) from the raw search
        // listings and distills them into detailed findings + a numbered claims list.
        // Reviewers then verify concrete claims instead of starving on URL snippets.
        val readerStep = {
            TaskStep(
                id = "step_read",
                description = "Read top sources and extract explicit claims",
                toolName = "invoke_subagent",
                toolArguments = JSONObject(
                    mapOf(
                        "role" to "Research Reader",
                        "goal" to "Raw web search results are supplied below. Use web_fetch to open the 2-3 " +
                                "most relevant URLs and read their FULL content — do not judge from snippets. " +
                                "Then output exactly two sections:\n" +
                                "FINDINGS — a detailed plain-language digest of the key developments.\n" +
                                "CLAIMS — a numbered list of every explicit factual claim found, " +
                                "one claim per line, statements only (no commentary).",
                        "max_steps" to 8
                    )
                ).toString(),
                expectedOutcome = "Detailed findings + numbered claims extracted from sources"
            )
        }

        val verificationStep = {
            if (multiReviewer) {
                val reviewerInstructions = "Independently verify each numbered claim from the CLAIMS section " +
                        "supplied below (focus: %s). Prefer web_fetch to read full sources rather than relying " +
                        "on search snippets. Report VERIFIED / CONTRADICTED / UNCERTAIN per claim with evidence " +
                        "and sources. End with: (a) a corrections list where every fixable issue is marked " +
                        "CORRECTION REQUIRED or ADDITION REQUIRED together with the corrected statement, and " +
                        "(b) a final verdict: PASS, PASS_WITH_CONDITIONS, or FAIL."
                TaskStep(
                    id = "step_review_parallel",
                    description = "Deploy parallel verification reviewers ($focusA / $focusB)",
                    toolName = "invoke_subagent",
                    toolArguments = JSONObject(
                        mapOf(
                            "tasks" to JSONArray(
                                listOf(
                                    JSONObject(
                                        mapOf(
                                            "role" to "Technical Claims Reviewer",
                                            "goal" to String.format(reviewerInstructions, focusA),
                                            "max_steps" to 8
                                        )
                                    ),
                                    JSONObject(
                                        mapOf(
                                            "role" to "Commercial Claims Reviewer",
                                            "goal" to String.format(reviewerInstructions, focusB),
                                            "max_steps" to 8
                                        )
                                    )
                                )
                            )
                        )
                    ).toString(),
                    expectedOutcome = "Independent verification from 2 parallel reviewers"
                )
            } else {
                TaskStep(
                    id = "step_review",
                    description = "Deploy a verification reviewer to check the findings",
                    toolName = "invoke_subagent",
                    toolArguments = JSONObject(
                        mapOf(
                            "role" to "Verification Reviewer",
                            "goal" to "Independently verify each numbered claim in the CLAIMS section supplied " +
                                    "below for this task: '$searchQuery'. Prefer web_fetch to read full sources " +
                                    "rather than relying on search snippets. " +
                                    "Report what is confirmed, what is contradicted, and what remains uncertain. " +
                                    "End with: (a) a corrections list where every fixable issue is marked " +
                                    "CORRECTION REQUIRED or ADDITION REQUIRED together with the corrected statement, " +
                                    "and (b) a final verdict: PASS, PASS_WITH_CONDITIONS, or FAIL.",
                            "max_steps" to 8
                        )
                    ).toString(),
                    expectedOutcome = "Findings verified with flagged discrepancies"
                )
            }
        }

        // Code-writing intents ("make X from scratch in workspace", "and another will make Y")
        // delegate to Code Engineer subagents — parallel when the goal requests a second file —
        // each self-verifying via workspace_shell (python3 <file>).
        val codeAction = Regex(
            """\b(make|create|write|build|implement|generate|code)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(goal)
        val codeTarget = Regex(
            """\b(python|\.py|script|transformer|model|program|function|class|module|neural|network)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(goal)
        val codeSurface = lower.contains("workspace") || lower.contains(".py") ||
                lower.contains("python") || lower.contains("script")

        val secondFileMatch = if (codeAction && codeTarget && codeSurface) {
            Regex("""\band\s+(another|a\s+second|second)\b""", RegexOption.IGNORE_CASE).find(goal)
        } else null

        if (codeAction && codeTarget && codeSurface) {
            val engineerInstructions = "You are a Code Engineer working in the on-device Linux workspace. %s " +
                    "Write the complete, working Python file using workspace_write_file " +
                    "(choose a clear filename ending in .py — full implementation, no placeholders). " +
                    "Then VERIFY it: run 'python3 <yourfile>.py' via workspace_shell and fix every error " +
                    "until it executes cleanly. Report the final filename and verification output."
            if (secondFileMatch != null) {
                val firstGoal = goal.substring(0, secondFileMatch.range.first).trim().trimEnd(',', ';', '.')
                val secondGoal = goal.substring(secondFileMatch.range.first).trim()
                steps.add(
                    TaskStep(
                        id = "step_code_a",
                        description = "Code Engineer A: ${firstGoal.take(70)}",
                        toolName = "invoke_subagent",
                        toolArguments = JSONObject(
                            mapOf(
                                "role" to "Code Engineer A",
                                "goal" to String.format(engineerInstructions, "Task: $firstGoal."),
                                "max_steps" to 10
                            )
                        ).toString(),
                        expectedOutcome = "Working Python file written and execution-verified"
                    )
                )
                steps.add(
                    TaskStep(
                        id = "step_code_b",
                        description = "Code Engineer B: ${secondGoal.take(70)}",
                        toolName = "invoke_subagent",
                        toolArguments = JSONObject(
                            mapOf(
                                "role" to "Code Engineer B",
                                "goal" to String.format(engineerInstructions, "Second deliverable — $secondGoal"),
                                "max_steps" to 10
                            )
                        ).toString(),
                        expectedOutcome = "Second Python file written and execution-verified"
                    )
                )
            } else {
                steps.add(
                    TaskStep(
                        id = "step_code",
                        description = "Code Engineer: ${goal.take(70)}",
                        toolName = "invoke_subagent",
                        toolArguments = JSONObject(
                            mapOf(
                                "role" to "Code Engineer",
                                "goal" to String.format(engineerInstructions, "Task: $goal"),
                                "max_steps" to 10
                            )
                        ).toString(),
                        expectedOutcome = "Working Python file written and execution-verified"
                    )
                )
            }
        } else when {
            (lower.contains("search") || lower.contains("find") || lower.contains("look up")) &&
            (lower.contains("save") || lower.contains("write") || lower.contains("file") || lower.contains("summary")) -> {
                steps.add(
                    TaskStep(
                        id = "step_1_search",
                        description = "Search web for information",
                        toolName = "web_search",
                        toolArguments = JSONObject(mapOf("query" to searchQuery, "max_results" to 5)).toString(),
                        expectedOutcome = "Search results returned"
                    )
                )
                if (wantsVerification) {
                    steps.add(readerStep())
                    steps.add(verificationStep())
                }
                steps.add(
                    TaskStep(
                        id = "step_2_save",
                        description = "Save findings to workspace file",
                        toolName = "workspace_write_file",
                        toolArguments = JSONObject(mapOf("path" to extractedFilename, "content" to "Summary for: $goal", "overwrite" to true)).toString(),
                        expectedOutcome = "File written successfully"
                    )
                )
            }
            lower.contains("search") || lower.contains("find") || lower.contains("lookup") || lower.contains("web") -> {
                steps.add(
                    TaskStep(
                        id = "step_1_search",
                        description = "Search web for information",
                        toolName = "web_search",
                        toolArguments = JSONObject(mapOf("query" to searchQuery, "max_results" to 5)).toString(),
                        expectedOutcome = "Search results returned"
                    )
                )
                if (wantsVerification) {
                    steps.add(readerStep())
                    steps.add(verificationStep())
                }
            }
            lower.contains("memory") || lower.contains("vault") || lower.contains("remember") -> {
                steps.add(
                    TaskStep(
                        id = "step_1_vault",
                        description = "Access memory vault",
                        toolName = "create_memory",
                        toolArguments = JSONObject(mapOf("title" to "Agent Note", "content" to goal)).toString(),
                        expectedOutcome = "Memory note processed"
                    )
                )
            }
            else -> {
                steps.add(
                    TaskStep(
                        id = "step_1_execute",
                        description = "Execute task",
                        toolName = "workspace_shell",
                        toolArguments = JSONObject(mapOf("command" to "echo '$goal'")).toString(),
                        expectedOutcome = "Command executed"
                    )
                )
            }
        }

        return TaskPlan(goal = goal, steps = steps.take(maxSteps).toMutableList())
    }

    /**
     * Santa-Method trigger: detects reviewer output that demands changes — explicit
     * correction markers or a failing verdict — in the (unwrapped) report text.
     */
    private fun reviewerDemandsCorrections(observation: ToolObservation?): Boolean {
        val text = observation?.payload?.takeIf { it.isNotBlank() }
            ?: observation?.summary
            ?: return false
        val sample = if (text.length > 100_000) text.substring(0, 100_000) else text
        return sample.contains("CORRECTION REQUIRED", ignoreCase = true) ||
                sample.contains("ADDITION REQUIRED", ignoreCase = true) ||
                (sample.contains("verdict", ignoreCase = true) &&
                        Regex("""\bFAIL\b""", RegexOption.IGNORE_CASE).containsMatchIn(sample))
    }

    /**
     * Builds a subagent goal that includes findings from all prior executed steps.
     * Subagents cannot see the parent conversation, so without this injection a
     * "verify the findings" step would receive nothing to verify.
     */
    private fun buildSubagentGoal(baseGoal: String, plan: TaskPlan, currentIndex: Int): String {
        if (currentIndex <= 0) return baseGoal
        val findings = plan.steps.take(currentIndex).mapNotNull { prior ->
            val obs = prior.observation ?: return@mapNotNull null
            val data = obs.payload?.takeIf { it.isNotBlank() }
                ?: obs.summary.takeIf { it.isNotBlank() }
            if (data.isNullOrBlank()) null else "${prior.description}:\n$data"
        }
        if (findings.isEmpty()) return baseGoal
        val contextBlock = findings.joinToString("\n\n---\n\n")
        val capped = if (contextBlock.length > MAX_SUBAGENT_CONTEXT_CHARS) {
            contextBlock.take(MAX_SUBAGENT_CONTEXT_CHARS) + "\n...[context capped for budget]"
        } else contextBlock
        return "$baseGoal\n\n" +
                "=== FINDINGS FROM PRIOR STEPS (this is the material you must work on / verify) ===\n$capped"
    }

    /**
     * Extracts a focused search query from a potentially multi-clause goal:
     * keeps only the first actionable clause and strips imperative filler verbs.
     */
    private fun extractSearchQuery(goal: String): String {
        val clauseSplit = Regex(
            """[.;\n]|\b(?:and\s+then|then|after\s+that|next|followed\s+by)\b""",
            RegexOption.IGNORE_CASE
        )
        var query = clauseSplit.split(goal).firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
            ?: goal.trim()
        query = query.replace(
            Regex(
                """^\s*(?:please\s+)?(?:research|search(?:\s+(?:for|about))?|find(?:\s+(?:info(?:rmation)?|details)?\s*(?:about|on|for))?|look\s+up|google|investigate)\s*[:\-]?\s*""",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
        return query.take(120).ifBlank { goal.trim().take(120) }
    }

    private fun synthesizeResults(goal: String, plan: TaskPlan): String {
        return buildString {
            appendLine("### Task Execution Summary")
            appendLine()
            appendLine("**Goal**: $goal")
            appendLine()
            appendLine("**Executed Steps:**")
            plan.steps.forEach { step ->
                val obs = step.observation
                appendLine("- **${step.description}** (`${step.toolName}`)")
                val payload = obs?.payload?.trim()
                if (!payload.isNullOrBlank() && payload != "{}") {
                    val cleanText = if (com.bit.util.SearchResultFormatter.isRawSearchResult(payload)) {
                        com.bit.util.SearchResultFormatter.format(payload)
                    } else {
                        try {
                            val obj = JSONObject(payload)
                        obj.optString("stdout").takeIf { it.isNotBlank() }
                            ?: obj.optString("output").takeIf { it.isNotBlank() }
                            ?: obj.optString("result").takeIf { it.isNotBlank() }
                            ?: obj.optString("message").takeIf { it.isNotBlank() }
                            ?: obj.optString("content").takeIf { it.isNotBlank() }
                                ?: payload
                        } catch (_: Exception) {
                            payload
                        }
                    }
                    if (cleanText.isNotBlank() && cleanText != "File saved successfully" && cleanText != "true") {
                        val preview = if (cleanText.length > 1200) cleanText.take(1200) + "\n..." else cleanText
                        appendLine("```")
                        appendLine(preview)
                        appendLine("```")
                    }
                }
            }
        }
    }
}
