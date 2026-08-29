package com.bit.models.messages

/**
 * Structured agent progress event emitted per harness state transition.
 * Lets the UI render real step cards instead of parsing a text log.
 */
data class StepEvent(
    val type: String,               // PLANNING, EXECUTING, TOOL_RESULT, APPROVAL, QUESTION, SUBAGENT, GATE, SELF_CORRECT, COMPLETE, FAILED
    val label: String,              // human-readable one-liner for the card
    val toolName: String? = null,   // associated tool, when applicable
    val stepIndex: Int? = null,     // 1-based position in the plan
    val totalSteps: Int? = null,
    val durationMs: Long? = null,   // tool/subagent execution duration when known
    val success: Boolean? = null,   // outcome flag for GATE/TOOL_RESULT events
    val timestampMs: Long = System.currentTimeMillis()
)
