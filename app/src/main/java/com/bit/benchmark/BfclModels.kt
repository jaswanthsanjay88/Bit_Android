package com.bit.benchmark

enum class BfclCategory(val label: String, val description: String) {
    SIMPLE("Simple Call", "Single-parameter straightforward tool execution"),
    MULTIPLE_ARGS("Multiple Args", "Multi-parameter structured input generation"),
    DISTRACTOR("Distractors", "Target tool alongside multiple irrelevant catalog tools"),
    NEGATIVE_NO_TOOL("Negative (No Tool)", "Conversational / coding query where NO tool should trigger")
}

data class ExpectedToolCall(
    val name: String,
    val arguments: Map<String, Any?>
)

data class BfclTestCase(
    val id: String,
    val title: String,
    val prompt: String,
    val category: BfclCategory,
    val availableTools: List<String>,
    val expectedCall: ExpectedToolCall?
)

data class ToolCallMatchResult(
    val isMatch: Boolean,
    val toolMatched: Boolean,
    val argsMatched: Boolean,
    val generatedCall: ExpectedToolCall?,
    val mismatchReason: String? = null
)

data class TestCaseResult(
    val testCase: BfclTestCase,
    val matchResult: ToolCallMatchResult,
    val rawResponse: String,
    val ttftMs: Float,
    val decodeTps: Float,
    val totalTimeMs: Float,
    val tokensEvaluated: Int,
    val tokensPredicted: Int
)

data class BenchmarkSummary(
    val modelName: String,
    val modelPath: String,
    val totalTests: Int,
    val passedCount: Int,
    val astMatchRate: Float,
    val toolSelectionAccuracy: Float,
    val argumentValidityRate: Float,
    val falsePositiveRate: Float,
    val avgTtftMs: Float,
    val avgDecodeTps: Float,
    val avgLatencyMs: Float,
    val peakMemoryMb: Float,
    val testResults: List<TestCaseResult>
)
