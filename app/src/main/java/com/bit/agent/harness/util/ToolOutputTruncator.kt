package com.bit.agent.harness.util

import android.content.Context
import android.util.Log
import com.bit.agent.harness.model.ToolObservation
import java.io.File

/**
 * Handles truncation of large tool outputs (> 32 KB) with automatic redirection
 * to sandboxed Linux workspace files at `/tool_outputs/<toolCallId>.txt`.
 */
object ToolOutputTruncator {
    private const val TAG = "ToolOutputTruncator"
    const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024       // 32 KB
    const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024   // 4 KB
    const val TOOL_OUTPUTS_DIR = "tool_outputs"

    /**
     * Truncates output text if it exceeds [MAX_TOOL_OUTPUT_CHARS] and shell access is available.
     * The full raw output is persisted to `/tool_outputs/<fileName>` in local storage.
     */
    fun maybeTruncate(
        context: Context,
        toolCallId: String,
        output: String,
        hasShellAccess: Boolean = true
    ): String {
        val filesDir = runCatching { context.filesDir }.getOrNull() ?: File(System.getProperty("java.io.tmpdir", "."))
        return maybeTruncate(filesDir, toolCallId, output, hasShellAccess)
    }

    /**
     * FilesDir-based overload for unit testability and pure file operations.
     */
    fun maybeTruncate(
        filesDir: File,
        toolCallId: String,
        output: String,
        hasShellAccess: Boolean = true
    ): String {
        if (output.length <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) {
            return output
        }

        val totalChars = output.length
        runCatching { Log.i(TAG, "Truncating tool output for '$toolCallId' ($totalChars chars > $MAX_TOOL_OUTPUT_CHARS max)") }

        val safeId = toolCallId.ifBlank { System.currentTimeMillis().toString() }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "$safeId.txt"

        val outputDir = File(filesDir, TOOL_OUTPUTS_DIR).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)

        try {
            outputFile.writeText(output, Charsets.UTF_8)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed to write full tool output to $outputFile: ${e.message}", e) }
            return output.take(TOOL_OUTPUT_PREVIEW_CHARS) + "\n\n[Truncated: $totalChars chars total]"
        }

        val preview = output.take(TOOL_OUTPUT_PREVIEW_CHARS)
        return buildString {
            appendLine("[Tool output truncated: $totalChars characters total]")
            appendLine("Full output saved to: /tool_outputs/$fileName")
            appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
            appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
            appendLine()
            append(preview)
        }
    }

    /**
     * Helper to truncate observation payload if it exceeds size limits.
     */
    fun maybeTruncateObservation(
        context: Context,
        toolCallId: String,
        observation: ToolObservation,
        hasShellAccess: Boolean = true
    ): ToolObservation {
        val payload = observation.payload ?: return observation
        if (payload.length <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) {
            return observation
        }

        val filesDir = runCatching { context.filesDir }.getOrNull() ?: File(System.getProperty("java.io.tmpdir", "."))
        return maybeTruncateObservation(filesDir, toolCallId, observation, hasShellAccess)
    }

    /**
     * FilesDir-based overload for observation truncation.
     */
    fun maybeTruncateObservation(
        filesDir: File,
        toolCallId: String,
        observation: ToolObservation,
        hasShellAccess: Boolean = true
    ): ToolObservation {
        val payload = observation.payload ?: return observation
        if (payload.length <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) {
            return observation
        }

        val truncatedText = maybeTruncate(filesDir, toolCallId, payload, hasShellAccess)
        val safeId = toolCallId.ifBlank { System.currentTimeMillis().toString() }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "$safeId.txt"
        val outputDir = File(filesDir, TOOL_OUTPUTS_DIR)
        val savedFile = File(outputDir, fileName)

        return observation.copy(
            payload = truncatedText,
            artifacts = if (savedFile.exists()) observation.artifacts + savedFile.absolutePath else observation.artifacts
        )
    }
}
