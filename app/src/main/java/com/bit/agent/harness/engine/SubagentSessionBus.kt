package com.bit.agent.harness.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live registry of subagent sessions so the UI can show the user that agents are
 * working FOR them — including a drill-down session view (mission prompt, thinking,
 * tool activity, result). Populated by SubagentRunner; read by chat UI screens.
 */
object SubagentSessionBus {

    data class ActivityLine(val round: Int, val text: String, val timestampMs: Long = System.currentTimeMillis())

    data class LiveSubagent(
        val id: String,
        val role: String,
        val missionPrompt: String,      // exact goal/prompt the main agent gave this subagent
        val status: String,             // RUNNING | COMPLETED | WARNING | FAILED
        val currentRound: Int = 0,
        val maxSteps: Int = 8,
        val startedAtMs: Long = System.currentTimeMillis(),
        val endedAtMs: Long? = null,
        val activity: List<ActivityLine> = emptyList(),
        val result: String? = null
    ) {
        val isRunning: Boolean get() = status == "RUNNING"
    }

    private val _sessions = MutableStateFlow<Map<String, LiveSubagent>>(emptyMap())
    val sessions: StateFlow<Map<String, LiveSubagent>> = _sessions.asStateFlow()

    fun start(id: String, role: String, missionPrompt: String, maxSteps: Int) {
        val session = LiveSubagent(
            id = id, role = role, missionPrompt = missionPrompt,
            status = "RUNNING", maxSteps = maxSteps
        )
        _sessions.value = _sessions.value + (id to session)
    }

    fun log(id: String, round: Int, text: String) {
        mutate(id) { it.copy(currentRound = round.coerceAtLeast(it.currentRound)) }
        appendLine(id, ActivityLine(round = round, text = text))
    }

    fun finish(id: String, status: String, result: String?) {
        mutate(id) {
            it.copy(status = status, endedAtMs = System.currentTimeMillis(), result = result?.take(6000))
        }
    }

    fun clear() { _sessions.value = emptyMap() }

    fun runningSessions(): List<LiveSubagent> = _sessions.value.values.filter { it.isRunning }

    private fun appendLine(id: String, line: ActivityLine) {
        mutate(id) { it.copy(activity = it.activity + line) }
    }

    private fun mutate(id: String, transform: (LiveSubagent) -> LiveSubagent) {
        val current = _sessions.value[id] ?: return
        _sessions.value = _sessions.value + (id to transform(current))
    }
}
