package com.bit.agent.harness.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool approval states for interactive human-in-the-loop gating.
 */
@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending -> false
    }
}

/**
 * Standard observation envelope returned after every tool execution in the Agent Harness.
 */
@Serializable
data class ToolObservation(
    val status: ObservationStatus,
    val summary: String,
    val payload: String? = null,
    val artifacts: List<String> = emptyList(),
    val recoveryHint: String? = null,
    val executionTimeMs: Long = 0L,
    val approvalState: ToolApprovalState = ToolApprovalState.Auto
) {
    val isSuccess: Boolean get() = status == ObservationStatus.SUCCESS

    companion object {
        private const val MAX_PAYLOAD_CHARS = 64 * 1024

        fun success(
            summary: String,
            payload: String? = null,
            artifacts: List<String> = emptyList(),
            executionTimeMs: Long = 0L,
            approvalState: ToolApprovalState = ToolApprovalState.Auto
        ): ToolObservation {
            val truncatedPayload = payload?.let { truncatePayload(it) }
            return ToolObservation(
                status = ObservationStatus.SUCCESS,
                summary = summary,
                payload = truncatedPayload,
                artifacts = artifacts,
                executionTimeMs = executionTimeMs,
                approvalState = approvalState
            )
        }

        fun warning(
            summary: String,
            payload: String? = null,
            recoveryHint: String? = null,
            artifacts: List<String> = emptyList(),
            executionTimeMs: Long = 0L,
            approvalState: ToolApprovalState = ToolApprovalState.Auto
        ): ToolObservation {
            val truncatedPayload = payload?.let { truncatePayload(it) }
            return ToolObservation(
                status = ObservationStatus.WARNING,
                summary = summary,
                payload = truncatedPayload,
                artifacts = artifacts,
                recoveryHint = recoveryHint,
                executionTimeMs = executionTimeMs,
                approvalState = approvalState
            )
        }

        fun error(
            summary: String,
            recoveryHint: String,
            payload: String? = null,
            executionTimeMs: Long = 0L,
            approvalState: ToolApprovalState = ToolApprovalState.Auto
        ): ToolObservation {
            val truncatedPayload = payload?.let { truncatePayload(it) }
            return ToolObservation(
                status = ObservationStatus.ERROR,
                summary = summary,
                payload = truncatedPayload,
                recoveryHint = recoveryHint,
                executionTimeMs = executionTimeMs,
                approvalState = approvalState
            )
        }

        private fun truncatePayload(text: String): String {
            return if (text.length > MAX_PAYLOAD_CHARS) {
                text.substring(0, MAX_PAYLOAD_CHARS) + "\n...[truncated for context budget]"
            } else {
                text
            }
        }
    }
}

@Serializable
enum class ObservationStatus {
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * Subagent task and result contracts for multi-agent DAG deployment.
 */
@Serializable
data class SubagentTask(
    val id: String,
    val role: String,
    val goal: String,
    val allowedTools: List<String> = emptyList(),
    val maxSteps: Int = 10
)

@Serializable
data class SubagentResult(
    val taskId: String,
    val role: String,
    val isSuccess: Boolean,
    val summary: String,
    val output: String,
    val artifacts: List<String> = emptyList(),
    val stepsCompleted: Int = 0,
    val claims: List<VerifiedClaim> = emptyList()
)

/**
 * Structured verification claim extracted from a reviewer subagent's report.
 * Populated only when the model emits the recommended JSON block; UI must
 * always fall back to rendering [SubagentResult.output] as plain markdown.
 */
@Serializable
data class VerifiedClaim(
    val claim: String,
    val status: String,             // VERIFIED | CONTRADICTED | UNCERTAIN (loosely validated)
    val sources: List<String> = emptyList(),
    val notes: String = ""
)
