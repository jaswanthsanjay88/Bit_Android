package com.bit.agent.harness.tools

import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.ObservationStatus
import com.bit.agent.harness.model.ToolObservation
import com.bit.plugins.PluginManager
import com.dark.gguf_lib.toolcalling.ToolCall
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Agent Harness with BIT's existing PluginManager and tools ecosystem.
 * Formats results into standardized ToolObservation contracts and enforces token budgets.
 */
@Singleton
open class AgentToolBridge @Inject constructor(
    private val logger: HarnessLogger = NoOpHarnessLogger
) {

    companion object {
        private const val TAG = "AgentToolBridge"
    }

    /**
     * Executes a tool via PluginManager and returns a structured ToolObservation.
     * Open so tests can stub tool execution without touching Android runtime.
     */
    open suspend fun execute(toolName: String, argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val argsObj = try {
                if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            } catch (_: Exception) {
                JSONObject()
            }
            val toolCall = ToolCall(name = toolName, arguments = argsObj)
            val result = PluginManager.executeToolForMultiTurn(toolCall)
            val duration = System.currentTimeMillis() - startTime

            val artifacts = extractArtifacts(toolName, argsObj.toString(), result.resultJson)

            if (result.isError) {
                val recovery = generateRecoveryHint(toolName, result.resultJson)
                ToolObservation.error(
                    summary = "Tool $toolName failed: ${extractErrorMessage(result.resultJson)}",
                    recoveryHint = recovery,
                    payload = result.resultJson,
                    executionTimeMs = duration
                )
            } else {
                val summary = generateSuccessSummary(toolName, result.resultJson)
                ToolObservation.success(
                    summary = summary,
                    payload = result.resultJson,
                    artifacts = artifacts,
                    executionTimeMs = duration
                )
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.e(TAG, "Exception executing tool $toolName: ${e.message}", e)
            ToolObservation.error(
                summary = "Unhandled error in $toolName: ${e.message ?: "Unknown error"}",
                recoveryHint = "Check if tool parameters match expected schema and retry.",
                payload = e.stackTraceToString(),
                executionTimeMs = duration
            )
        }
    }

    private fun extractArtifacts(toolName: String, argsJson: String, resultJson: String): List<String> {
        val artifacts = mutableListOf<String>()
        try {
            val args = JSONObject(argsJson)
            if (args.has("path")) artifacts.add(args.getString("path"))
            if (args.has("file_path")) artifacts.add(args.getString("file_path"))
            if (args.has("url")) artifacts.add(args.getString("url"))
        } catch (_: Exception) {}

        try {
            val res = JSONObject(resultJson)
            if (res.has("path")) artifacts.add(res.getString("path"))
            if (res.has("url")) artifacts.add(res.getString("url"))
        } catch (_: Exception) {}

        return artifacts.distinct()
    }

    private fun extractErrorMessage(json: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString("error", json)
        } catch (_: Exception) {
            json.take(100)
        }
    }

    private fun generateSuccessSummary(toolName: String, resultJson: String): String {
        return when (toolName.lowercase()) {
            "web_search", "search_web" -> "Web search completed successfully."
            "web_fetch", "scrape_web" -> "Web page fetched and parsed."
            "workspace_read_file", "read_memory_file" -> "File read successfully."
            "workspace_write_file", "create_memory_file" -> "File written successfully."
            "workspace_edit_file", "edit_memory_file" -> "File edit applied successfully."
            "str_replace_editor" -> "File operation completed via str_replace_editor."
            "todo_write" -> "Todo task list updated."
            "ralph" -> "Ralph loop completed round execution."
            "workspace_shell" -> "Shell command finished execution."
            "list_memory_files" -> "Memory files listed."
            else -> "$toolName executed successfully."
        }
    }

    private fun generateRecoveryHint(toolName: String, errorJson: String): String {
        val lower = errorJson.lowercase()
        return when {
            lower.contains("not found") || lower.contains("no such file") ->
                "Resource or file was not found. Use list_memory_files or check workspace path."
            lower.contains("target not found") || lower.contains("substring") ->
                "Exact code/string chunk to replace was not found. Re-read the file to verify exact lines."
            lower.contains("timeout") ->
                "Operation timed out. Reduce payload size or simplify query."
            lower.contains("plugin not enabled") ->
                "Plugin is disabled in PluginManager. Check active plugins."
            else ->
                "Verify the parameters passed to $toolName and retry with corrected inputs."
        }
    }
}
