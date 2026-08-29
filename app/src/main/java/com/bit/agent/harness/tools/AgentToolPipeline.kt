package com.bit.agent.harness.tools

import android.content.Context
import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.ToolObservation
import com.bit.agent.harness.util.ToolOutputTruncator
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pluggable interceptor interface for the guarded tool execution pipeline.
 */
interface ToolInterceptor {
    suspend fun preExecute(tool: AgentTool, rawArgumentsJson: String): String = rawArgumentsJson
    suspend fun postExecute(tool: AgentTool, observation: ToolObservation): ToolObservation = observation
}

/**
 * Guarded 3-Phase Tool Execution Pipeline (DeepSeek Harness paradigm).
 * Executes tools across three distinct phases:
 * Phase 1: pre-execute (interception, argument validation, security sandbox)
 * Phase 2: execute (invoking the underlying tool action space)
 * Phase 3: post-execute (interception, token budgeting, truncation, artifact extraction)
 */
@Singleton
class AgentToolPipeline @Inject constructor(
    private val logger: HarnessLogger = NoOpHarnessLogger
) {
    private val interceptors = mutableListOf<ToolInterceptor>()

    fun addInterceptor(interceptor: ToolInterceptor) {
        interceptors.add(interceptor)
    }

    suspend fun execute(
        tool: AgentTool,
        rawArgumentsJson: String,
        context: Context? = null,
        toolCallId: String = ""
    ): ToolObservation {
        val startTime = System.currentTimeMillis()
        var validatedArgs = rawArgumentsJson

        // Phase 1: Pre-Execution
        try {
            for (interceptor in interceptors) {
                validatedArgs = interceptor.preExecute(tool, validatedArgs)
            }
        } catch (e: Exception) {
            logger.e("AgentToolPipeline", "Pre-execution interceptor failed for '${tool.definition.function.name}': ${e.message}")
            return ToolObservation.error(
                summary = "Pre-execution policy denied tool '${tool.definition.function.name}': ${e.message}",
                recoveryHint = "Review arguments and adjust permissions.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Phase 2: Execution
        var observation: ToolObservation = try {
            tool.execute(validatedArgs)
        } catch (e: Exception) {
            logger.e("AgentToolPipeline", "Execution failed for '${tool.definition.function.name}': ${e.message}", e)
            ToolObservation.error(
                summary = "Unhandled tool error in '${tool.definition.function.name}': ${e.message ?: "Unknown error"}",
                recoveryHint = "Check parameters against tool definition and retry.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Phase 3: Post-Execution
        try {
            if (context != null && toolCallId.isNotBlank()) {
                observation = ToolOutputTruncator.maybeTruncateObservation(
                    context = context,
                    toolCallId = toolCallId,
                    observation = observation,
                    hasShellAccess = true
                )
            }
            for (interceptor in interceptors) {
                observation = interceptor.postExecute(tool, observation)
            }
        } catch (e: Exception) {
            logger.w("AgentToolPipeline", "Post-execution interceptor warning: ${e.message}")
        }

        return observation
    }
}
