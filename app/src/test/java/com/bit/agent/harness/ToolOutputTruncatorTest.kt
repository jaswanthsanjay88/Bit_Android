package com.bit.agent.harness

import com.bit.agent.harness.model.ToolObservation
import com.bit.agent.harness.util.ToolOutputTruncator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolOutputTruncatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testOutputUnderThresholdNotTruncated() {
        val testDir = tempFolder.newFolder("files")
        val shortOutput = "Short output of 100 characters. " + "A".repeat(80)

        val result = ToolOutputTruncator.maybeTruncate(
            filesDir = testDir,
            toolCallId = "test_call_1",
            output = shortOutput,
            hasShellAccess = true
        )

        assertEquals(shortOutput, result)
        assertFalse(File(testDir, "tool_outputs").exists())
    }

    @Test
    fun testOutputOverThresholdTruncatedAndSavedToFile() {
        val testDir = tempFolder.newFolder("files")
        val totalChars = 40 * 1024 // 40 KB
        val largeOutput = "LINE_START\n" + "X".repeat(totalChars - 20) + "\nLINE_END"

        val result = ToolOutputTruncator.maybeTruncate(
            filesDir = testDir,
            toolCallId = "call_abc123",
            output = largeOutput,
            hasShellAccess = true
        )

        assertTrue(result.contains("[Tool output truncated: $totalChars characters total]"))
        assertTrue(result.contains("Full output saved to: /tool_outputs/call_abc123.txt"))
        assertTrue(result.contains("Use shell to read: `cat /tool_outputs/call_abc123.txt`"))
        assertTrue(result.contains("Use shell to search: `grep \"pattern\" /tool_outputs/call_abc123.txt`"))

        // Check preview length
        assertTrue(result.length < 5 * 1024)

        // Check persisted file
        val savedFile = File(testDir, "tool_outputs/call_abc123.txt")
        assertTrue(savedFile.exists())
        assertEquals(largeOutput, savedFile.readText(Charsets.UTF_8))
    }

    @Test
    fun testTruncateObservation() {
        val testDir = tempFolder.newFolder("files")
        val totalChars = 50 * 1024 // 50 KB
        val largePayload = "Y".repeat(totalChars)
        val initialObs = ToolObservation.success(
            summary = "Command completed",
            payload = largePayload,
            artifacts = listOf("/workspace/input.txt")
        )

        val truncatedObs = ToolOutputTruncator.maybeTruncateObservation(
            filesDir = testDir,
            toolCallId = "step_obs_1",
            observation = initialObs,
            hasShellAccess = true
        )

        assertTrue(truncatedObs.payload!!.contains("[Tool output truncated: $totalChars characters total]"))
        assertTrue(truncatedObs.artifacts.any { it.endsWith("step_obs_1.txt") })
        assertTrue(truncatedObs.artifacts.contains("/workspace/input.txt"))
    }
}
