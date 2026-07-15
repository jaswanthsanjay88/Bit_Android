package com.bit.plugins

import android.util.Log
import com.bit.worker.LlmModelWorker
import com.bit.engine.GenerationEvent
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import org.json.JSONObject
import org.json.JSONArray

object TwoStageToolRouter {
    private const val TAG = "TwoStageToolRouter"

    /**
     * Orchestrates the 2-stage tool routing flow.
     * Stage 1: Select the tool name (or "none") using Stage 1 GBNF grammar constraint.
     * Stage 2: Generate the parameters matching the selected tool's schema using Stage 2 GBNF grammar constraint.
     *
     * @return Pair of (toolName, argumentsJsonString) if a tool is successfully selected and parameterized, otherwise null.
     */
    suspend fun route(
        messages: List<JSONObject>,
        enabledTools: List<ToolDefinitionBuilder>
    ): Pair<String, String>? {
        val toolNames = enabledTools.map { it.name }
        if (toolNames.isEmpty()) {
            Log.d(TAG, "No tools enabled, skipping 2-stage routing")
            return null
        }

        // --- STAGE 1: Tool Selection ---
        Log.d(TAG, "Stage 1: Tool Selection starting. Available tools: $toolNames")
        val stage1Grammar = GbnfGenerator.generateStage1(toolNames)
        
        // Enforce Stage 1 grammar constraint
        LlmModelWorker.setCustomGrammarGguf(stage1Grammar)
        
        val messagesJson = JSONArray(messages).toString()
        val stage1ResultBuilder = StringBuilder()
        
        try {
            LlmModelWorker.ggufGenerateMultiTurnStreaming(messagesJson, maxTokens = 128).collect { event ->
                if (event is GenerationEvent.Token) {
                    stage1ResultBuilder.append(event.text)
                }
            }
        } finally {
            LlmModelWorker.clearCustomGrammarGguf()
        }

        val stage1Output = stage1ResultBuilder.toString().trim()
        Log.d(TAG, "Stage 1 output: $stage1Output")

        val selectedToolName = extractToolName(stage1Output)
        if (selectedToolName == null || selectedToolName.equals("none", ignoreCase = true)) {
            Log.d(TAG, "Stage 1: No tool selected or 'none' returned.")
            return null
        }

        Log.d(TAG, "Stage 1: Selected tool '$selectedToolName'")

        // Find the tool definition
        val chosenTool = enabledTools.find { it.name.lowercase() == selectedToolName.lowercase() }
        if (chosenTool == null) {
            Log.w(TAG, "Selected tool '$selectedToolName' is not in the enabled tools list.")
            return null
        }

        // --- STAGE 2: Arguments Generation ---
        Log.d(TAG, "Stage 2: Arguments Generation starting for tool '$selectedToolName'")
        val openAI = chosenTool.build().toOpenAIFormat()
        val functionObj = openAI.optJSONObject("function") ?: openAI
        val parametersObj = functionObj.optJSONObject("parameters") ?: JSONObject()
        val propertiesObj = parametersObj.optJSONObject("properties") ?: JSONObject()

        val stage2Grammar = GbnfGenerator.generateStage2(chosenTool.name, propertiesObj)
        
        // Build Stage 2 prompt messages context
        val stage2Messages = messages.toMutableList().apply {
            add(JSONObject().apply {
                put("role", "system")
                put("content", "You have decided to call the tool '${chosenTool.name}'. Now, generate the exact arguments required for '${chosenTool.name}' to address the user's request. Output ONLY the tool call tag: <tool_call>{\"name\":\"${chosenTool.name}\",\"arguments\":{...}}</tool_call>")
            })
        }

        // Enforce Stage 2 grammar constraint
        LlmModelWorker.setCustomGrammarGguf(stage2Grammar)
        
        val stage2ResultBuilder = StringBuilder()
        
        try {
            LlmModelWorker.ggufGenerateMultiTurnStreaming(
                JSONArray(stage2Messages).toString(),
                maxTokens = 512
            ).collect { event ->
                if (event is GenerationEvent.Token) {
                    stage2ResultBuilder.append(event.text)
                }
            }
        } finally {
            LlmModelWorker.clearCustomGrammarGguf()
        }

        val stage2Output = stage2ResultBuilder.toString().trim()
        Log.d(TAG, "Stage 2 output: $stage2Output")

        val argumentsObj = extractArguments(stage2Output)
        if (argumentsObj == null) {
            Log.e(TAG, "Stage 2: Failed to extract arguments from output: $stage2Output")
            return null
        }

        val argumentsStr = argumentsObj.toString()
        Log.i(TAG, "2-stage routing success: tool=$selectedToolName, args=$argumentsStr")
        return Pair(chosenTool.name, argumentsStr)
    }

    private fun extractToolName(text: String): String? {
        val startTag = "<tool_call>"
        val endTag = "</tool_call>"
        val startIndex = text.indexOf(startTag)
        val endIndex = text.indexOf(endTag)
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            // Fallback: try parsing as raw JSON if tags are missing
            return try {
                val json = JSONObject(text.trim())
                json.optString("name").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        val jsonContent = text.substring(startIndex + startTag.length, endIndex).trim()
        return try {
            val json = JSONObject(jsonContent)
            json.optString("name").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractArguments(text: String): JSONObject? {
        val startTag = "<tool_call>"
        val endTag = "</tool_call>"
        val startIndex = text.indexOf(startTag)
        val endIndex = text.indexOf(endTag)
        if (startIndex == -1 || endIndex == -1 || endIndex <= startIndex) {
            // Fallback: try parsing as raw JSON if tags are missing
            return try {
                val json = JSONObject(text.trim())
                json.optJSONObject("arguments")
            } catch (e: Exception) {
                null
            }
        }
        val jsonContent = text.substring(startIndex + startTag.length, endIndex).trim()
        return try {
            val json = JSONObject(jsonContent)
            json.optJSONObject("arguments")
        } catch (e: Exception) {
            null
        }
    }
}
