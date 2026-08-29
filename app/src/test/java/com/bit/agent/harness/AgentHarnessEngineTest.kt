package com.bit.agent.harness

import com.bit.agent.harness.gate.SelfCorrectionPlanner
import com.bit.agent.harness.gate.StepGateChecker
import com.bit.agent.harness.engine.AgentHarnessEngine
import com.bit.agent.harness.model.ObservationStatus
import com.bit.agent.harness.model.ToolObservation
import com.bit.agent.harness.state.AgentHarnessState
import com.bit.agent.harness.state.StepStatus
import com.bit.agent.harness.state.TaskPlan
import com.bit.agent.harness.state.TaskStep
import com.bit.agent.harness.tools.AgentToolBridge
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHarnessEngineTest {

    @Test
    fun testToolObservationTruncation() {
        val longContent = "A".repeat(70000)
        val obs = ToolObservation.success(
            summary = "Success",
            payload = longContent,
            artifacts = listOf("/workspace/file.txt")
        )

        assertEquals(ObservationStatus.SUCCESS, obs.status)
        assertNotNull(obs.payload)
        assertTrue(obs.payload!!.contains("[truncated for context budget]"))
        assertEquals(1, obs.artifacts.size)
    }

    @Test
    fun testStepGateCheckerPassing() {
        val gateChecker = StepGateChecker()
        val step = TaskStep(
            id = "step_1",
            description = "Search web for information",
            toolName = "web_search",
            toolArguments = "{\"query\":\"Kotlin\"}",
            expectedOutcome = "Search results returned"
        )
        val obs = ToolObservation.success(
            summary = "Web search completed successfully.",
            payload = "{\"results\": [\"Kotlin 2.0 release\"]}"
        )

        val result = gateChecker.verify(step, obs)
        assertTrue(result.passed)
    }

    @Test
    fun testStepGateCheckerFailingOnError() {
        val gateChecker = StepGateChecker()
        val step = TaskStep(
            id = "step_1",
            description = "Read workspace file",
            toolName = "workspace_read_file",
            toolArguments = "{\"path\":\"missing.txt\"}",
            expectedOutcome = "File content returned"
        )
        val obs = ToolObservation.error(
            summary = "File not found",
            recoveryHint = "Check if path exists using directory list.",
            payload = "{\"error\": \"No such file\"}"
        )

        val result = gateChecker.verify(step, obs)
        assertFalse(result.passed)
        assertNotNull(result.recoveryHint)
    }

    @Test
    fun testSelfCorrectionPlanner() {
        val planner = SelfCorrectionPlanner()
        val step = TaskStep(
            id = "step_1",
            description = "Search web",
            toolName = "web_search",
            toolArguments = "{\"query\":\"\\\"quotes\\\"\"}",
            expectedOutcome = "Search results"
        )
        val obs = ToolObservation.error(
            summary = "Search failed",
            recoveryHint = "Simplify query"
        )
        val gateChecker = StepGateChecker()
        val gateResult = gateChecker.verify(step, obs)

        val plan = planner.planCorrection(step, gateResult, obs)
        assertNotNull(plan.revisedArguments)
        assertFalse(plan.revisedArguments.contains("\\\"quotes\\\""))
    }

    @Test
    fun testParsePlanJson() {
        val engine = com.bit.agent.harness.engine.AgentHarnessEngine(
            toolBridge = AgentToolBridge(),
            gateChecker = StepGateChecker(),
            correctionPlanner = SelfCorrectionPlanner()
        )

        val json = """
            [
                {
                    "id": "step_1",
                    "description": "Search DuckDuckGo",
                    "toolName": "web_search",
                    "arguments": {"query": "Android Jetpack"},
                    "expectedOutcome": "Results retrieved"
                },
                {
                    "id": "step_2",
                    "description": "Write to file",
                    "toolName": "workspace_write_file",
                    "arguments": {"path": "result.txt", "text": "test"},
                    "expectedOutcome": "File saved"
                }
            ]
        """.trimIndent()

        val plan = engine.parsePlanJson("Test Goal", json)
        assertEquals("Test Goal", plan.goal)
        assertEquals(2, plan.steps.size)
        assertEquals("step_1", plan.steps[0].id)
        assertEquals("web_search", plan.steps[0].toolName)
        assertEquals("step_2", plan.steps[1].id)
        assertEquals("workspace_write_file", plan.steps[1].toolName)
    }

    @Test
    fun testDecomposeToDagPlanAndFormatMarkdown() {
        val engine = com.bit.agent.harness.engine.AgentHarnessEngine(
            toolBridge = AgentToolBridge(),
            gateChecker = StepGateChecker(),
            correctionPlanner = SelfCorrectionPlanner()
        )

        val plan = engine.decomposeToDagPlan("Search latest Kotlin release and save to file")
        assertTrue(plan.steps.size >= 2)
        assertEquals("web_search", plan.steps[0].toolName)
        assertEquals("workspace_write_file", plan.steps[1].toolName)

        val markdown = engine.formatPlanToMarkdown(plan, activeStepIndex = 1)
        assertTrue(markdown.contains("Execution Plan (DAG)"))
        assertTrue(markdown.contains("[RUNNING]"))
        assertTrue(markdown.contains("web_search"))
        assertTrue(markdown.contains("workspace_write_file"))
    }

    // ------------------------------------------------------------------
    // executeGoal FSM coverage
    // ------------------------------------------------------------------

    /** Stub bridge returning queued observations; records every tool invocation. */
    private class FakeToolBridge(
        private val results: MutableList<ToolObservation>
    ) : AgentToolBridge() {
        val executedTools = mutableListOf<String>()

        override suspend fun execute(toolName: String, argumentsJson: String): ToolObservation {
            executedTools.add(toolName)
            return if (results.isEmpty()) {
                ToolObservation.error(summary = "No stub result", recoveryHint = "n/a")
            } else {
                results.removeAt(0)
            }
        }
    }

    private fun oneStepPlanJson(toolName: String = "workspace_shell") = """
        [{"id":"step_1","description":"Do the thing","toolName":"$toolName",
          "arguments":{"command":"echo hi"},"expectedOutcome":"Command finished"}]
    """.trimIndent()

    private fun buildEngine(
        results: MutableList<ToolObservation>,
        planner: ((String) -> String?)? = { goal -> oneStepPlanJson() }
    ): Pair<AgentHarnessEngine, FakeToolBridge> {
        val bridge = FakeToolBridge(results)
        val engine = com.bit.agent.harness.engine.AgentHarnessEngine(
            toolBridge = bridge,
            gateChecker = StepGateChecker(),
            correctionPlanner = SelfCorrectionPlanner(),
            planGenerator = planner?.let { f ->
                com.bit.agent.harness.engine.PlanGenerator { goal -> f(goal) }
            }
        )
        return engine to bridge
    }

    @Test
    fun testExecuteGoalHappyPathEmitsFullSequence() = runBlocking {
        val (engine, bridge) = buildEngine(
            results = mutableListOf(ToolObservation.success(summary = "echo ran", payload = "hi"))
        )
        val states = engine.executeGoal("say hi").toList()

        assertTrue(states.first() is AgentHarnessState.Decomposing)
        assertTrue(states.any { it is AgentHarnessState.Executing })
        assertTrue(states.any { it is AgentHarnessState.GateChecking && it.passed })
        val completed = states.last()
        assertTrue(completed is AgentHarnessState.Completed)
        assertEquals(1, (completed as AgentHarnessState.Completed).totalTurns)
        assertEquals(listOf("workspace_shell"), bridge.executedTools)
        assertEquals(AgentHarnessState.Completed::class, engine.state.value::class)
    }

    @Test
    fun testGateFailureThenSelfCorrectThenComplete() = runBlocking {
        var call = 0
        val bridge = object : AgentToolBridge() {
            override suspend fun execute(toolName: String, argumentsJson: String): ToolObservation {
                return if (call++ == 0) {
                    ToolObservation.error(summary = "boom", recoveryHint = "retry once")
                } else {
                    ToolObservation.success(summary = "ok", payload = "{\"done\":true}")
                }
            }
        }
        val retryEngine = com.bit.agent.harness.engine.AgentHarnessEngine(
            toolBridge = bridge,
            gateChecker = StepGateChecker(),
            correctionPlanner = SelfCorrectionPlanner(),
            planGenerator = com.bit.agent.harness.engine.PlanGenerator { _ -> oneStepPlanJson() }
        )

        val states = retryEngine.executeGoal("flaky task", maxRetriesPerStep = 3).toList()

        val selfCorrect = states.filterIsInstance<AgentHarnessState.SelfCorrecting>()
        assertEquals(1, selfCorrect.size)
        assertEquals(1, selfCorrect[0].retryCount)
        assertTrue(states.last() is AgentHarnessState.Completed)
    }

    @Test
    fun testExhaustedRetriesFailsGracefully() = runBlocking {
        val (engine, _) = buildEngine(
            results = mutableListOf(
                ToolObservation.error("fail 1", "hint"),
                ToolObservation.error("fail 2", "hint"),
                ToolObservation.error("fail 3", "hint"),
                ToolObservation.error("fail 4", "hint")
            )
        )
        val states = engine.executeGoal("doomed task", maxRetriesPerStep = 3).toList()

        val failed = states.last()
        assertTrue(failed is AgentHarnessState.Failed)
        assertTrue((failed as AgentHarnessState.Failed).reason.contains("failed validation"))
        assertEquals(3, failed.step?.retryCount)
    }

    @Test
    fun testGlobalMaxStepsGuardTripsOnEndlessFailure() = runBlocking {
        val (engine, _) = buildEngine(
            results = mutableListOf(
                ToolObservation.error("fail 1", "hint"),
                ToolObservation.error("fail 2", "hint")
            ),
            planner = { _ -> oneStepPlanJson() }
        )
        // maxSteps=1: first turn executes and fails; budget exhausted before any retry.
        val states = engine.executeGoal("stuck task", maxSteps = 1, maxRetriesPerStep = 3).toList()

        val failed = states.last()
        assertTrue(failed is AgentHarnessState.Failed)
        assertTrue((failed as AgentHarnessState.Failed).reason.contains("Global step budget"))
    }

    @Test
    fun testAlternativeToolSwapAppliedAfterRepeatedRetry() = runBlocking {
        var call = 0
        val executed = mutableListOf<String>()
        val bridge = object : AgentToolBridge() {
            override suspend fun execute(toolName: String, argumentsJson: String): ToolObservation {
                executed.add(toolName)
                return if (call++ < 2) {
                    ToolObservation.error(summary = "search backend down", recoveryHint = "try fetch directly")
                } else {
                    ToolObservation.success(summary = "fetched", payload = "{\"content\":\"page body\"}")
                }
            }
        }
        val engine = com.bit.agent.harness.engine.AgentHarnessEngine(
            toolBridge = bridge,
            gateChecker = StepGateChecker(),
            correctionPlanner = SelfCorrectionPlanner(),
            planGenerator = com.bit.agent.harness.engine.PlanGenerator { _ -> oneStepPlanJson(toolName = "web_search") }
        )

        val states = engine.executeGoal("fetch page", maxRetriesPerStep = 3).toList()

        // After the 2nd failure (retryCount=2 > 1), SelfCorrectionPlanner proposes web_fetch.
        assertEquals(listOf("web_search", "web_search", "web_fetch"), executed)
        assertTrue(states.last() is AgentHarnessState.Completed)
    }

    @Test
    fun testMalformedLlmPlanFallsBackToHeuristics() = runBlocking {
        val (engine, bridge) = buildEngine(
            results = mutableListOf(ToolObservation.success(summary = "ran", payload = "out")),
            planner = { _ -> "not valid json at all {" }
        )
        val states = engine.executeGoal("hello world").toList()

        // Heuristic fallback for a non-search/memory goal is workspace_shell echo.
        assertEquals(listOf("workspace_shell"), bridge.executedTools)
        assertTrue(states.last() is AgentHarnessState.Completed)
    }

    @Test
    fun testResetReturnsToIdle() {
        val (engine, _) = buildEngine(results = mutableListOf())
        engine.reset()
        assertTrue(engine.state.value is AgentHarnessState.Idle)
    }

    @Test
    fun testTodoWriteToolValidationAndCounts() = runBlocking {
        val todoTool = com.bit.agent.harness.tools.TodoWriteTool(allowParallelInProgress = false)

        val validJson = """
            {
                "todos": [
                    {"content": "Step 1", "status": "completed"},
                    {"content": "Step 2", "status": "in_progress"},
                    {"content": "Step 3", "status": "pending"}
                ]
            }
        """.trimIndent()

        val obs = todoTool.execute(validJson)
        assertEquals(ObservationStatus.SUCCESS, obs.status)
        assertTrue(obs.summary.contains("1 pending, 1 in progress, 1 completed"))

        // Duplicate test
        val duplicateJson = """
            {
                "todos": [
                    {"content": "Same task", "status": "pending"},
                    {"content": "Same task", "status": "in_progress"}
                ]
            }
        """.trimIndent()
        val dupObs = todoTool.execute(duplicateJson)
        assertEquals(ObservationStatus.ERROR, dupObs.status)
        assertTrue(dupObs.summary.contains("Duplicate task content"))

        // Multiple in_progress test
        val multiActiveJson = """
            {
                "todos": [
                    {"content": "Task A", "status": "in_progress"},
                    {"content": "Task B", "status": "in_progress"}
                ]
            }
        """.trimIndent()
        val multiObs = todoTool.execute(multiActiveJson)
        assertEquals(ObservationStatus.ERROR, multiObs.status)
        assertTrue(multiObs.summary.contains("At most one task may be 'in_progress'"))
    }

    @Test
    fun testRalphToolLoopCompletion() = runBlocking {
        var roundCount = 0
        val stubExecutor = com.bit.agent.harness.tools.SubagentExecutor { task ->
            roundCount++
            if (roundCount == 1) {
                com.bit.agent.harness.model.SubagentResult(
                    taskId = task.id,
                    role = task.role,
                    isSuccess = true,
                    summary = "Round 1 progress",
                    output = """{"status":"continue","summary":"Wrote skeleton","evidence":["file.py created"],"nextSteps":["Add tests"],"blocker":""}""",
                    artifacts = listOf("file.py"),
                    stepsCompleted = 1
                )
            } else {
                com.bit.agent.harness.model.SubagentResult(
                    taskId = task.id,
                    role = task.role,
                    isSuccess = true,
                    summary = "Objective complete",
                    output = """{"status":"complete","summary":"All features done and verified","evidence":["tests passing"],"nextSteps":[],"blocker":""}""",
                    artifacts = listOf("test.py"),
                    stepsCompleted = 1
                )
            }
        }

        val ralphTool = com.bit.agent.harness.tools.RalphTool(stubExecutor)
        val args = """{"objective": "Build neural net", "max_rounds": 5}"""
        val obs = ralphTool.execute(args)

        assertEquals(ObservationStatus.SUCCESS, obs.status)
        assertEquals(2, roundCount)
        assertTrue(obs.summary.contains("completed objective after 2 rounds"))
        assertTrue(obs.artifacts.contains("file.py"))
        assertTrue(obs.artifacts.contains("test.py"))
    }

    @Test
    fun testStrReplaceEditorOperations() = runBlocking {
        val tempFile = java.io.File.createTempFile("test_editor", ".txt")
        tempFile.deleteOnExit()
        tempFile.delete() // Start absent for 'create'

        val editorTool = com.bit.agent.harness.tools.StrReplaceEditorTool()

        // 1. Create
        val createArgs = org.json.JSONObject().apply {
            put("command", "create")
            put("path", tempFile.absolutePath)
            put("file_text", "Line 1\nTarget line\nLine 3")
        }.toString()
        val createObs = editorTool.execute(createArgs)
        assertEquals(ObservationStatus.SUCCESS, createObs.status)

        // 2. View
        val viewArgs = org.json.JSONObject().apply {
            put("command", "view")
            put("path", tempFile.absolutePath)
            put("view_range", org.json.JSONArray(listOf(1, 3)))
        }.toString()
        val viewObs = editorTool.execute(viewArgs)
        assertEquals(ObservationStatus.SUCCESS, viewObs.status)
        assertTrue(viewObs.payload!!.contains("Line 1"))
        assertTrue(viewObs.payload!!.contains("Target line"))

        // 3. str_replace
        val replaceArgs = org.json.JSONObject().apply {
            put("command", "str_replace")
            put("path", tempFile.absolutePath)
            put("old_str", "Target line")
            put("new_str", "Replaced line")
        }.toString()
        val replaceObs = editorTool.execute(replaceArgs)
        assertEquals(ObservationStatus.SUCCESS, replaceObs.status)
        assertEquals("Line 1\nReplaced line\nLine 3", tempFile.readText())

        // 4. Insert
        val insertArgs = org.json.JSONObject().apply {
            put("command", "insert")
            put("path", tempFile.absolutePath)
            put("insert_line", 2)
            put("new_str", "Inserted line")
        }.toString()
        val insertObs = editorTool.execute(insertArgs)
        assertEquals(ObservationStatus.SUCCESS, insertObs.status)
        assertEquals("Line 1\nReplaced line\nInserted line\nLine 3", tempFile.readText())
    }

    @Test
    fun testOptimisticVersionCheckingInEditor() = runBlocking {
        val tempFile = java.io.File.createTempFile("test_version", ".txt")
        tempFile.deleteOnExit()
        tempFile.writeText("Original text")

        val editorTool = com.bit.agent.harness.tools.StrReplaceEditorTool()

        // Conflict test: wrong expected_version fails gracefully
        val conflictArgs = org.json.JSONObject().apply {
            put("command", "str_replace")
            put("path", tempFile.absolutePath)
            put("old_str", "Original")
            put("new_str", "Modified")
            put("expected_version", "invalid_stale_hash_999")
        }.toString()

        val conflictObs = editorTool.execute(conflictArgs)
        assertEquals(ObservationStatus.ERROR, conflictObs.status)
        assertTrue(conflictObs.summary.contains("Version conflict"))
    }

    @Test
    fun testHarnessSessionLogEventSourcingAndMessageDerivation() {
        val log = com.bit.agent.harness.model.HarnessSessionLog()
        log.append(com.bit.agent.harness.model.HarnessSessionEvent.TurnStart("turn_1", "Inspect project architecture"))
        log.append(com.bit.agent.harness.model.HarnessSessionEvent.StepStart("turn_1", "step_1", "str_replace_editor", "{\"command\":\"view\"}"))
        log.append(
            com.bit.agent.harness.model.HarnessSessionEvent.ToolResultRecorded(
                "turn_1",
                "step_1",
                "str_replace_editor",
                ToolObservation.success("Viewed file", "File content here")
            )
        )
        log.append(com.bit.agent.harness.model.HarnessSessionEvent.StepEnd("turn_1", "step_1", "PASSED"))
        log.append(com.bit.agent.harness.model.HarnessSessionEvent.TurnEnd("turn_1", "Inspection complete", true))

        assertEquals(5, log.events.size)

        val messages = log.deriveMessages(systemPrompt = "You are BIT Agent")
        assertEquals(5, messages.size)
        assertEquals(com.bit.api.Participant.USER, messages[0].participant)
        assertEquals("You are BIT Agent", messages[0].text)
        assertEquals(com.bit.api.Participant.USER, messages[1].participant)
        assertEquals("Inspect project architecture", messages[1].text)
        assertEquals(com.bit.api.Participant.MODEL, messages[2].participant)
        assertTrue(messages[2].text.contains("str_replace_editor"))
        assertEquals(com.bit.api.Participant.USER, messages[3].participant)
        assertTrue(messages[3].text.contains("File content here"))
        assertEquals(com.bit.api.Participant.MODEL, messages[4].participant)
        assertEquals("Inspection complete", messages[4].text)
    }

    @Test
    fun testAgentToolPipeline3PhaseExecution() = runBlocking {
        val pipeline = com.bit.agent.harness.tools.AgentToolPipeline()

        var preExecuted = false
        var postExecuted = false

        pipeline.addInterceptor(object : com.bit.agent.harness.tools.ToolInterceptor {
            override suspend fun preExecute(tool: com.bit.agent.harness.tools.AgentTool, rawArgumentsJson: String): String {
                preExecuted = true
                return rawArgumentsJson
            }

            override suspend fun postExecute(tool: com.bit.agent.harness.tools.AgentTool, observation: ToolObservation): ToolObservation {
                postExecuted = true
                return observation.copy(summary = observation.summary + " [intercepted]")
            }
        })

        val stubTool = object : com.bit.agent.harness.tools.AgentTool {
            override val definition = com.bit.api.ToolDefinition(
                type = "function",
                function = com.bit.api.ToolFunction(
                    name = "test_tool",
                    description = "test",
                    parameters = com.bit.api.ToolParameters(properties = emptyMap())
                )
            )
            override suspend fun execute(argumentsJson: String) = ToolObservation.success("ran", "ok")
        }

        val obs = pipeline.execute(stubTool, "{}")
        assertTrue(preExecuted)
        assertTrue(postExecuted)
        assertTrue(obs.summary.contains("[intercepted]"))
    }
}
