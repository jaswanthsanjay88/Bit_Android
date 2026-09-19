package com.bit.benchmark

import org.json.JSONObject
import java.util.Locale

object BfclAstMatcher {

    private val TOOL_CALL_REGEX = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val JSON_BLOCK_REGEX = Regex(
        """```(?:json)?\s*(\{\s*"name"\s*:.*?\})\s*```""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Extracts a tool call (name and arguments map) from raw generated text.
     */
    fun parseToolCall(raw: String): ExpectedToolCall? {
        val trimmed = raw.trim()

        // 1. Try standard <tool_call>...</tool_call>
        TOOL_CALL_REGEX.find(trimmed)?.let { match ->
            val jsonStr = match.groupValues[1]
            parseJsonObject(jsonStr)?.let { return it }
        }

        // 2. Try markdown code block with {"name": ...}
        JSON_BLOCK_REGEX.find(trimmed)?.let { match ->
            val jsonStr = match.groupValues[1]
            parseJsonObject(jsonStr)?.let { return it }
        }

        // 3. Try raw JSON string if output starts with {
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            parseJsonObject(trimmed)?.let { return it }
        }

        // 4. Fallback: Search for any {"name": ..., "arguments": ...} substring
        val startIdx = trimmed.indexOf("{\"name\"")
        if (startIdx != -1) {
            val endIdx = trimmed.lastIndexOf("}")
            if (endIdx > startIdx) {
                val candidate = trimmed.substring(startIdx, endIdx + 1)
                parseJsonObject(candidate)?.let { return it }
            }
        }

        return null
    }

    private fun parseJsonObject(jsonStr: String): ExpectedToolCall? {
        return try {
            val obj = JSONObject(jsonStr)
            val name = obj.optString("name").ifEmpty {
                obj.optString("function")
            }
            if (name.isEmpty()) return null

            val argsMap = mutableMapOf<String, Any?>()
            val argsRaw = obj.opt("arguments") ?: obj.opt("parameters")

            if (argsRaw is JSONObject) {
                val keys = argsRaw.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    argsMap[key] = argsRaw.get(key)
                }
            } else if (argsRaw is String) {
                try {
                    val parsed = JSONObject(argsRaw)
                    val keys = parsed.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = parsed.get(key)
                    }
                } catch (_: Exception) {
                    argsMap["raw"] = argsRaw
                }
            }

            ExpectedToolCall(name = name, arguments = argsMap)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Evaluates a generated output against the ground truth AST expectation.
     */
    fun evaluate(rawOutput: String, expected: ExpectedToolCall?): ToolCallMatchResult {
        val generated = parseToolCall(rawOutput)

        // Negative test: Expected NO tool call
        if (expected == null) {
            return if (generated == null) {
                ToolCallMatchResult(
                    isMatch = true,
                    toolMatched = true,
                    argsMatched = true,
                    generatedCall = null,
                    mismatchReason = null
                )
            } else {
                ToolCallMatchResult(
                    isMatch = false,
                    toolMatched = false,
                    argsMatched = false,
                    generatedCall = generated,
                    mismatchReason = "Hallucinated tool call '${generated.name}' on negative prompt"
                )
            }
        }

        // Positive test: Expected a tool call
        if (generated == null) {
            return ToolCallMatchResult(
                isMatch = false,
                toolMatched = false,
                argsMatched = false,
                generatedCall = null,
                mismatchReason = "Model did not emit a tool call for expected tool '${expected.name}'"
            )
        }

        val toolNameMatched = generated.name.equals(expected.name, ignoreCase = true) ||
                normalize(generated.name) == normalize(expected.name)

        if (!toolNameMatched) {
            return ToolCallMatchResult(
                isMatch = false,
                toolMatched = false,
                argsMatched = false,
                generatedCall = generated,
                mismatchReason = "Tool name mismatch: expected '${expected.name}', got '${generated.name}'"
            )
        }

        val argsMatchResult = compareArguments(expected.arguments, generated.arguments)
        val isFullMatch = toolNameMatched && argsMatchResult.first

        return ToolCallMatchResult(
            isMatch = isFullMatch,
            toolMatched = toolNameMatched,
            argsMatched = argsMatchResult.first,
            generatedCall = generated,
            mismatchReason = if (isFullMatch) null else argsMatchResult.second
        )
    }

    private fun compareArguments(
        expected: Map<String, Any?>,
        actual: Map<String, Any?>
    ): Pair<Boolean, String?> {
        for ((expKey, expVal) in expected) {
            // Find matching key case-insensitively or with punctuation differences
            val actualEntry = actual.entries.find {
                normalize(it.key) == normalize(expKey)
            }

            if (actualEntry == null) {
                return Pair(false, "Missing required argument '$expKey'")
            }

            val actVal = actualEntry.value
            if (!valuesMatch(expVal, actVal)) {
                return Pair(false, "Argument '$expKey' value mismatch: expected '$expVal', got '$actVal'")
            }
        }

        return Pair(true, null)
    }

    private fun valuesMatch(expected: Any?, actual: Any?): Boolean {
        if (expected == null && actual == null) return true
        if (expected == null || actual == null) return false

        // Numeric comparison (handle Float, Double, Int, Long)
        val expNum = toDoubleOrNull(expected)
        val actNum = toDoubleOrNull(actual)
        if (expNum != null && actNum != null) {
            return Math.abs(expNum - actNum) < 1e-4
        }

        // Boolean comparison
        val expBool = toBooleanOrNull(expected)
        val actBool = toBooleanOrNull(actual)
        if (expBool != null && actBool != null) {
            return expBool == actBool
        }

        // String comparison with trimmed semantic normalization
        val expStr = expected.toString().trim()
        val actStr = actual.toString().trim()

        return expStr.equals(actStr, ignoreCase = true) ||
                normalize(expStr) == normalize(actStr)
    }

    private fun toDoubleOrNull(v: Any): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun toBooleanOrNull(v: Any): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.lowercase(Locale.US).toBooleanStrictOrNull()
        else -> null
    }

    private fun normalize(s: String): String {
        return s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
    }
}
