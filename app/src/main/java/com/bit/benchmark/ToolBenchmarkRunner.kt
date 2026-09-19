package com.bit.benchmark

import android.util.Log
import com.bit.models.table_schema.Model
import com.bit.worker.LlmModelWorker
import com.bit.engine.GenerationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolBenchmarkRunner(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _progressIndex = MutableStateFlow(0)
    val progressIndex: StateFlow<Int> = _progressIndex.asStateFlow()

    private val _totalTests = MutableStateFlow(0)
    val totalTests: StateFlow<Int> = _totalTests.asStateFlow()

    private val _currentPrompt = MutableStateFlow("")
    val currentPrompt: StateFlow<String> = _currentPrompt.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to benchmark")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _completedResults = MutableStateFlow<List<TestCaseResult>>(emptyList())
    val completedResults: StateFlow<List<TestCaseResult>> = _completedResults.asStateFlow()

    private val _summary = MutableStateFlow<BenchmarkSummary?>(null)
    val summary: StateFlow<BenchmarkSummary?> = _summary.asStateFlow()

    private var benchmarkJob: Job? = null

    companion object {
        private const val TAG = "ToolBenchmarkRunner"

        // Tool schema definitions used during benchmark
        val BENCHMARK_TOOLS_SCHEMA = mapOf(
            "calculator" to """{"name": "calculator", "description": "Performs mathematical calculations", "parameters": {"type": "object", "properties": {"expression": {"type": "string", "description": "The math expression to evaluate, e.g. 48 * 15 or sqrt(144)"}}, "required": ["expression"]}}""",
            "get_weather" to """{"name": "get_weather", "description": "Fetches current weather for a city", "parameters": {"type": "object", "properties": {"city": {"type": "string", "description": "City name"}}, "required": ["city"]}}""",
            "web_search" to """{"name": "web_search", "description": "Searches the internet for real-time information", "parameters": {"type": "object", "properties": {"query": {"type": "string", "description": "Search query keywords"}, "count": {"type": "integer", "description": "Number of search results"}}, "required": ["query"]}}""",
            "unit_convert" to """{"name": "unit_convert", "description": "Converts between units of measurement", "parameters": {"type": "object", "properties": {"value": {"type": "number", "description": "Quantity to convert"}, "from": {"type": "string", "description": "Source unit"}, "to": {"type": "string", "description": "Target unit"}}, "required": ["value", "from", "to"]}}""",
            "notes_save" to """{"name": "notes_save", "description": "Saves a note to local memory storage", "parameters": {"type": "object", "properties": {"title": {"type": "string", "description": "Title of note"}, "body": {"type": "string", "description": "Content of note"}}, "required": ["title", "body"]}}""",
            "system_info" to """{"name": "system_info", "description": "Retrieves device system information like battery, storage, or memory", "parameters": {"type": "object", "properties": {"category": {"type": "string", "description": "Category such as battery, storage, or memory"}}, "required": ["category"]}}"""
        )

        val DEFAULT_TEST_SUITE = listOf(
            // ── Category 1: Simple Single-Parameter Calls ──
            BfclTestCase(
                id = "calc_simple",
                title = "Calculator: Multiplication",
                prompt = "What is 48 multiplied by 15?",
                category = BfclCategory.SIMPLE,
                availableTools = listOf("calculator"),
                expectedCall = ExpectedToolCall("calculator", mapOf("expression" to "48 * 15"))
            ),
            BfclTestCase(
                id = "weather_simple",
                title = "Weather: Current City",
                prompt = "What is the weather in Paris right now?",
                category = BfclCategory.SIMPLE,
                availableTools = listOf("get_weather"),
                expectedCall = ExpectedToolCall("get_weather", mapOf("city" to "Paris"))
            ),

            // ── Category 2: Multiple Arguments ──
            BfclTestCase(
                id = "web_search_multi",
                title = "Web Search: Query & Count",
                prompt = "Search the web for Kotlin 2.0 release updates with 5 results.",
                category = BfclCategory.MULTIPLE_ARGS,
                availableTools = listOf("web_search"),
                expectedCall = ExpectedToolCall("web_search", mapOf("query" to "Kotlin 2.0 release updates", "count" to 5))
            ),
            BfclTestCase(
                id = "unit_convert_multi",
                title = "Unit Convert: Value, From, To",
                prompt = "Convert 100 kilometers to miles.",
                category = BfclCategory.MULTIPLE_ARGS,
                availableTools = listOf("unit_convert"),
                expectedCall = ExpectedToolCall("unit_convert", mapOf("value" to 100.0, "from" to "kilometers", "to" to "miles"))
            ),

            // ── Category 3: Distractors (Pick 1 of 6 available tools) ──
            BfclTestCase(
                id = "distractor_calc",
                title = "Distractor: Pick Calculator",
                prompt = "Calculate the square root of 144.",
                category = BfclCategory.DISTRACTOR,
                availableTools = listOf("calculator", "get_weather", "web_search", "unit_convert", "notes_save", "system_info"),
                expectedCall = ExpectedToolCall("calculator", mapOf("expression" to "sqrt(144)"))
            ),
            BfclTestCase(
                id = "distractor_note",
                title = "Distractor: Pick Notes Save",
                prompt = "Save a note titled 'Groceries' with content 'Milk, Bread, Eggs'.",
                category = BfclCategory.DISTRACTOR,
                availableTools = listOf("calculator", "get_weather", "web_search", "unit_convert", "notes_save", "system_info"),
                expectedCall = ExpectedToolCall("notes_save", mapOf("title" to "Groceries", "body" to "Milk, Bread, Eggs"))
            ),
            BfclTestCase(
                id = "distractor_system",
                title = "Distractor: Pick System Info",
                prompt = "Check the free storage on this device.",
                category = BfclCategory.DISTRACTOR,
                availableTools = listOf("calculator", "get_weather", "web_search", "unit_convert", "notes_save", "system_info"),
                expectedCall = ExpectedToolCall("system_info", mapOf("category" to "storage"))
            ),

            // ── Category 4: Negative (NO tool should be triggered) ──
            BfclTestCase(
                id = "neg_coding",
                title = "Negative: Python Code (No Tool)",
                prompt = "Write a Python function to check if a number is prime.",
                category = BfclCategory.NEGATIVE_NO_TOOL,
                availableTools = listOf("calculator", "web_search", "system_info"),
                expectedCall = null
            ),
            BfclTestCase(
                id = "neg_creative",
                title = "Negative: Creative Poem (No Tool)",
                prompt = "Write a four line poem about the night sky and stars.",
                category = BfclCategory.NEGATIVE_NO_TOOL,
                availableTools = listOf("calculator", "get_weather", "notes_save"),
                expectedCall = null
            ),
            BfclTestCase(
                id = "neg_reasoning",
                title = "Negative: Conceptual Difference (No Tool)",
                prompt = "Explain the core difference between a stack and a queue data structure.",
                category = BfclCategory.NEGATIVE_NO_TOOL,
                availableTools = listOf("calculator", "web_search", "unit_convert"),
                expectedCall = null
            )
        )
    }

    fun startBenchmark(
        model: Model,
        testSuite: List<BfclTestCase> = DEFAULT_TEST_SUITE,
        warmupFirst: Boolean = true
    ) {
        if (_isRunning.value) return
        _isRunning.value = true
        _progressIndex.value = 0
        _totalTests.value = testSuite.size
        _completedResults.value = emptyList()
        _summary.value = null
        _statusMessage.value = "Starting benchmark on ${model.modelName}..."

        benchmarkJob = scope.launch {
            try {
                val resultsList = mutableListOf<TestCaseResult>()

                // Warmup round to ensure weights are paged in
                if (warmupFirst) {
                    _statusMessage.value = "Running warm-up round..."
                    runSinglePrompt("Hi, reply with one word: 'READY'.", emptyList(), maxTokens = 8)
                }

                var index = 0
                for (testCase in testSuite) {
                    index++
                    _progressIndex.value = index
                    _currentPrompt.value = testCase.prompt
                    _statusMessage.value = "Running test $index/${testSuite.size}: ${testCase.title}..."

                    val executionResult = executeTestCase(testCase)
                    resultsList.add(executionResult)
                    _completedResults.value = resultsList.toList()
                }

                // Compute summary statistics
                val total = resultsList.size
                val passed = resultsList.count { it.matchResult.isMatch }
                val astMatchRate = if (total > 0) (passed.toFloat() / total) * 100f else 0f

                val positiveTests = resultsList.filter { it.testCase.expectedCall != null }
                val toolSelectionAcc = if (positiveTests.isNotEmpty()) {
                    (positiveTests.count { it.matchResult.toolMatched }.toFloat() / positiveTests.size) * 100f
                } else 100f

                val argValidity = if (positiveTests.isNotEmpty()) {
                    (positiveTests.count { it.matchResult.argsMatched }.toFloat() / positiveTests.size) * 100f
                } else 100f

                val negativeTests = resultsList.filter { it.testCase.expectedCall == null }
                val falsePositives = negativeTests.count { !it.matchResult.isMatch }
                val falsePositiveRate = if (negativeTests.isNotEmpty()) {
                    (falsePositives.toFloat() / negativeTests.size) * 100f
                } else 0f

                val validTtfts = resultsList.map { it.ttftMs }.filter { it > 0f }
                val avgTtft = if (validTtfts.isNotEmpty()) validTtfts.average().toFloat() else 0f

                val validTps = resultsList.map { it.decodeTps }.filter { it > 0f }
                val avgTps = if (validTps.isNotEmpty()) validTps.average().toFloat() else 0f

                val avgLatency = if (resultsList.isNotEmpty()) resultsList.map { it.totalTimeMs }.average().toFloat() else 0f

                val summaryObj = BenchmarkSummary(
                    modelName = model.modelName,
                    modelPath = model.modelPath,
                    totalTests = total,
                    passedCount = passed,
                    astMatchRate = astMatchRate,
                    toolSelectionAccuracy = toolSelectionAcc,
                    argumentValidityRate = argValidity,
                    falsePositiveRate = falsePositiveRate,
                    avgTtftMs = avgTtft,
                    avgDecodeTps = avgTps,
                    avgLatencyMs = avgLatency,
                    peakMemoryMb = 0f,
                    testResults = resultsList
                )

                _summary.value = summaryObj
                _statusMessage.value = "Benchmark completed! Passed $passed/$total (${String.format(java.util.Locale.US, "%.1f", astMatchRate)}%)"
            } catch (e: kotlinx.coroutines.CancellationException) {
                _statusMessage.value = "Benchmark cancelled."
            } catch (e: Exception) {
                Log.e(TAG, "Error running benchmark", e)
                _statusMessage.value = "Error: ${e.message}"
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun cancel() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _isRunning.value = false
        _statusMessage.value = "Benchmark stopped."
    }

    private suspend fun executeTestCase(testCase: BfclTestCase): TestCaseResult {
        val toolSchemas = testCase.availableTools.mapNotNull { BENCHMARK_TOOLS_SCHEMA[it] }
        val prompt = buildBenchmarkPrompt(testCase.prompt, toolSchemas)

        val t0 = System.currentTimeMillis()
        var ttftMs = 0f
        var decodeTps = 0f
        var totalTimeMs = 0f
        var tokensEvaluated = 0
        var tokensPredicted = 0
        val textBuilder = StringBuilder()

        val messagesJson = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }.toString()

        LlmModelWorker.ggufGenerateMultiTurnStreaming(messagesJson, maxTokens = 256).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    if (ttftMs == 0f) {
                        ttftMs = (System.currentTimeMillis() - t0).toFloat()
                    }
                    textBuilder.append(event.text)
                }
                is GenerationEvent.Metrics -> {
                    decodeTps = event.metrics.tokensPerSecond
                    if (event.metrics.timeToFirstTokenMs > 0f) ttftMs = event.metrics.timeToFirstTokenMs
                    totalTimeMs = event.metrics.totalTimeMs
                    tokensEvaluated = event.metrics.tokensEvaluated
                    tokensPredicted = event.metrics.tokensPredicted
                }
                else -> {}
            }
        }

        if (totalTimeMs == 0f) {
            totalTimeMs = (System.currentTimeMillis() - t0).toFloat()
        }

        val rawOutput = textBuilder.toString()
        val matchResult = BfclAstMatcher.evaluate(rawOutput, testCase.expectedCall)

        return TestCaseResult(
            testCase = testCase,
            matchResult = matchResult,
            rawResponse = rawOutput,
            ttftMs = ttftMs,
            decodeTps = decodeTps,
            totalTimeMs = totalTimeMs,
            tokensEvaluated = tokensEvaluated,
            tokensPredicted = tokensPredicted
        )
    }

    private suspend fun runSinglePrompt(prompt: String, toolSchemas: List<String>, maxTokens: Int = 128): String {
        val fullPrompt = buildBenchmarkPrompt(prompt, toolSchemas)
        val textBuilder = StringBuilder()
        val messagesJson = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", fullPrompt)
            })
        }.toString()

        LlmModelWorker.ggufGenerateMultiTurnStreaming(messagesJson, maxTokens).collect { event ->
            if (event is GenerationEvent.Token) {
                textBuilder.append(event.text)
            }
        }
        return textBuilder.toString()
    }

    private fun buildBenchmarkPrompt(userPrompt: String, toolSchemas: List<String>): String {
        if (toolSchemas.isEmpty()) return userPrompt

        val toolsArray = JSONArray()
        for (schema in toolSchemas) {
            try {
                toolsArray.put(JSONObject(schema))
            } catch (_: Exception) {}
        }

        return buildString {
            append("<tools>\n")
            append(toolsArray.toString(2))
            append("\n</tools>\n\n")
            append("If a tool is relevant to answer the query, call it by outputting:\n")
            append("<tool_call>{\"name\": \"tool_name\", \"arguments\": {\"arg\": \"val\"}}</tool_call>\n")
            append("If no tool is needed, directly answer the user without calling any tools.\n\n")
            append(userPrompt)
        }
    }
}
