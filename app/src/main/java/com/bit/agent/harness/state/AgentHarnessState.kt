package com.bit.agent.harness.state

import com.bit.agent.harness.model.SubagentTask
import com.bit.agent.harness.model.ToolApprovalState
import com.bit.agent.harness.model.ToolObservation
import kotlinx.serialization.Serializable

/**
 * Reactive sealed hierarchy for the on-device Agent Harness finite state machine.
 */
sealed class AgentHarnessState {
    data object Idle : AgentHarnessState()

    data class Decomposing(
        val prompt: String,
        val attempt: Int = 1
    ) : AgentHarnessState()

    data class Executing(
        val activeStep: TaskStep,
        val toolName: String,
        val stepIndex: Int,
        val totalSteps: Int
    ) : AgentHarnessState()

    data class AwaitingApproval(
        val activeStep: TaskStep,
        val toolName: String,
        val toolArguments: String,
        val approvalState: ToolApprovalState.Pending
    ) : AgentHarnessState()

    data class SubagentRunning(
        val subagentTask: SubagentTask,
        val parentStepIndex: Int,
        val totalParentSteps: Int
    ) : AgentHarnessState()

    data class GateChecking(
        val step: TaskStep,
        val observation: ToolObservation,
        val passed: Boolean
    ) : AgentHarnessState()

    data class SelfCorrecting(
        val failedStep: TaskStep,
        val rootCause: String,
        val recoveryHint: String,
        val retryCount: Int,
        val maxRetries: Int = 3
    ) : AgentHarnessState()

    data class Completed(
        val finalResult: String,
        val artifacts: List<String>,
        val totalTurns: Int,
        val executionTimeMs: Long
    ) : AgentHarnessState()

    data class Failed(
        val reason: String,
        val step: TaskStep? = null,
        val partialArtifacts: List<String> = emptyList()
    ) : AgentHarnessState()
}

@Serializable
data class TaskPlan(
    val goal: String,
    val steps: MutableList<TaskStep> = mutableListOf()
)

@Serializable
data class TaskStep(
    val id: String,
    val description: String,
    var toolName: String,
    var toolArguments: String = "{}",
    val expectedOutcome: String,
    var status: StepStatus = StepStatus.PENDING,
    var observation: ToolObservation? = null,
    var retryCount: Int = 0
)

@Serializable
enum class StepStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED
}
