package com.bit.agent.harness.gate

import com.bit.agent.harness.model.ToolObservation
import com.bit.agent.harness.state.TaskStep
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class CorrectionPlan(
    val revisedArguments: String,
    val alternativeToolName: String? = null,
    val explanation: String
)

/**
 * Plans corrective actions when a step fails the validation gate.
 */
@Singleton
class SelfCorrectionPlanner @Inject constructor() {

    fun planCorrection(
        step: TaskStep,
        gateResult: GateResult,
        observation: ToolObservation
    ): CorrectionPlan {
        val rootCause = gateResult.reason
        val hint = gateResult.recoveryHint ?: observation.recoveryHint ?: "Review arguments and retry."

        // Heuristic argument adjustment for common tools
        val adjustedArgs = try {
            val obj = JSONObject(step.toolArguments)
            when (step.toolName.lowercase()) {
                "web_search", "search_web" -> {
                    // Simplify query if too long or quotes failed
                    val q = obj.optString("query", "")
                    if (q.contains("\"")) {
                        obj.put("query", q.replace("\"", ""))
                    }
                    obj.toString()
                }
                "workspace_edit_file" -> {
                    // If target chunk wasn't found, ensure replace_all is false and path is clean
                    obj.put("replace_all", false)
                    obj.toString()
                }
                else -> step.toolArguments
            }
        } catch (_: Exception) {
            step.toolArguments
        }

        val altTool = when (step.toolName.lowercase()) {
            "search_web" -> "web_search"
            "web_search" -> "web_fetch"
            "workspace_read_file" -> "workspace_shell"
            else -> null
        }

        return CorrectionPlan(
            revisedArguments = adjustedArgs,
            alternativeToolName = if (step.retryCount > 1) altTool else null,
            explanation = "Correction plan for '${step.id}': $rootCause. Action: $hint"
        )
    }

    fun buildCorrectionPrompt(
        step: TaskStep,
        gateResult: GateResult,
        observation: ToolObservation
    ): String {
        return buildString {
            appendLine("The execution of step '${step.id}' failed the verification gate.")
            appendLine("Step Description: ${step.description}")
            appendLine("Tool Used: ${step.toolName}")
            appendLine("Arguments: ${step.toolArguments}")
            appendLine("Failure Reason: ${gateResult.reason}")
            if (!gateResult.recoveryHint.isNullOrBlank()) {
                appendLine("Recovery Guidance: ${gateResult.recoveryHint}")
            }
            appendLine("Please generate corrected tool arguments or a corrective action to achieve: ${step.expectedOutcome}")
        }
    }
}
