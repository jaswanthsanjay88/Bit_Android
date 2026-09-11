package com.bit.agent.harness.tools

import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.ToolObservation
import org.json.JSONObject

/**
 * Tool allowing the agent to ask the user clarification or confirmation questions.
 */
class AskUserTool(
    private val logger: HarnessLogger = NoOpHarnessLogger
) : AgentTool {

    companion object {
        const val NAME = "ask_user"
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = "Ask the user clarification questions or request decision feedback when requirements are ambiguous.",
            parameters = ToolParameters(
                properties = mapOf(
                    "question" to ToolProperty(
                        type = "string",
                        description = "The question text to ask the user"
                    ),
                    "options" to ToolProperty(
                        type = "array",
                        description = "Optional suggested choices for the user to select from"
                    )
                ),
                required = listOf("question")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            val question = args.optString("question").trim()
            if (question.isEmpty()) {
                return ToolObservation.error(
                    summary = "ask_user requires a 'question' argument.",
                    recoveryHint = "Provide a specific question for the user."
                )
            }

            ToolObservation.success(
                summary = "Question posed to user: $question",
                payload = "User prompt: $question",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            logger.e("AskUserTool", "ask_user failed: ${e.message}", e)
            ToolObservation.error(
                summary = "ask_user failed: ${e.message}",
                recoveryHint = "Retry asking the user with simplified wording.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
