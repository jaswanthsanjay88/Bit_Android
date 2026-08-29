package com.bit.agent.harness.tools

import com.bit.api.ToolDefinition
import com.bit.agent.harness.model.ToolObservation

/**
 * A single executable tool in the harness action space.
 * Supports dynamic system prompt sections, approval requirements, and ordering (DeepSeek Harness paradigm).
 */
interface AgentTool {
    val definition: ToolDefinition
    val requiresApproval: Boolean get() = false
    val promptOrder: Int get() = 100
    val systemPromptContribution: String? get() = getSystemPrompt().takeIf { it.isNotBlank() }

    fun needsApproval(argumentsJson: String): Boolean = requiresApproval
    fun getSystemPrompt(): String = ""
    suspend fun execute(argumentsJson: String): ToolObservation
}

