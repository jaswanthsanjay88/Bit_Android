package com.bit.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BfclAstMatcherTest {

    @Test
    fun testParseStandardToolCallTag() {
        val raw = "Thinking...\n<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"48 * 15\"}}</tool_call>"
        val parsed = BfclAstMatcher.parseToolCall(raw)

        assertNotNull(parsed)
        assertEquals("calculator", parsed!!.name)
        assertEquals("48 * 15", parsed.arguments["expression"])
    }

    @Test
    fun testParseMarkdownJsonToolCall() {
        val raw = "```json\n{\n  \"name\": \"get_weather\",\n  \"arguments\": {\"city\": \"Paris\"}\n}\n```"
        val parsed = BfclAstMatcher.parseToolCall(raw)

        assertNotNull(parsed)
        assertEquals("get_weather", parsed!!.name)
        assertEquals("Paris", parsed.arguments["city"])
    }

    @Test
    fun testEvaluatePositiveSimpleMatch() {
        val raw = "<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"48 * 15\"}}</tool_call>"
        val expected = ExpectedToolCall("calculator", mapOf("expression" to "48 * 15"))

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertTrue(result.isMatch)
        assertTrue(result.toolMatched)
        assertTrue(result.argsMatched)
        assertNull(result.mismatchReason)
    }

    @Test
    fun testEvaluateNumericTolerance() {
        // Model outputs 100.0 or 100, should both match 100
        val raw = "<tool_call>{\"name\": \"unit_convert\", \"arguments\": {\"value\": 100.0, \"from\": \"km\", \"to\": \"miles\"}}</tool_call>"
        val expected = ExpectedToolCall("unit_convert", mapOf("value" to 100, "from" to "km", "to" to "miles"))

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertTrue(result.isMatch)
        assertTrue(result.argsMatched)
    }

    @Test
    fun testEvaluateWrongToolSelected() {
        val raw = "<tool_call>{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}</tool_call>"
        val expected = ExpectedToolCall("calculator", mapOf("expression" to "48 * 15"))

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertFalse(result.isMatch)
        assertFalse(result.toolMatched)
        assertTrue(result.mismatchReason!!.contains("Tool name mismatch"))
    }

    @Test
    fun testEvaluateMissingArgument() {
        val raw = "<tool_call>{\"name\": \"unit_convert\", \"arguments\": {\"value\": 100}}</tool_call>"
        val expected = ExpectedToolCall("unit_convert", mapOf("value" to 100, "from" to "km", "to" to "miles"))

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertFalse(result.isMatch)
        assertTrue(result.toolMatched)
        assertFalse(result.argsMatched)
        assertTrue(result.mismatchReason!!.contains("Missing required argument"))
    }

    @Test
    fun testEvaluateNegativeNoToolExpectedPass() {
        val raw = "Here is a Python function to check prime numbers:\n```python\ndef is_prime(n):\n    return n > 1\n```"
        val expected: ExpectedToolCall? = null

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertTrue(result.isMatch)
        assertNull(result.generatedCall)
    }

    @Test
    fun testEvaluateNegativeHallucinatedToolFail() {
        val raw = "<tool_call>{\"name\": \"calculator\", \"arguments\": {\"expression\": \"2 + 2\"}}</tool_call>"
        val expected: ExpectedToolCall? = null

        val result = BfclAstMatcher.evaluate(raw, expected)
        assertFalse(result.isMatch)
        assertNotNull(result.generatedCall)
        assertTrue(result.mismatchReason!!.contains("Hallucinated tool call"))
    }
}
