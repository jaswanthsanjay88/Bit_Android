package com.bit.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class WorkspaceProcessType {
    INTERACTIVE_TERMINAL,
    AI_AGENT_COMMAND
}

enum class WorkspaceProcessStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    KILLED
}

data class WorkspaceProcess(
    val id: String = UUID.randomUUID().toString(),
    val workspaceId: String,
    val title: String,
    val command: String,
    val type: WorkspaceProcessType,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val status: WorkspaceProcessStatus = WorkspaceProcessStatus.RUNNING,
    val exitCode: Int? = null,
    val output: String = "",
    val sessionIndex: Int = 1
) {
    val durationText: String
        get() {
            val end = endTime ?: System.currentTimeMillis()
            val sec = ((end - startTime) / 1000L).coerceAtLeast(0L)
            return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
        }
}

object WorkspaceProcessManager {
    private val _processes = MutableStateFlow<List<WorkspaceProcess>>(emptyList())
    val processes: StateFlow<List<WorkspaceProcess>> = _processes.asStateFlow()

    private var terminalCounter = 1

    /**
     * Registers a new AI-initiated command execution.
     */
    fun registerAiCommand(workspaceId: String, command: String): String {
        val id = UUID.randomUUID().toString()
        val truncatedCmd = if (command.length > 40) command.take(37) + "..." else command
        val process = WorkspaceProcess(
            id = id,
            workspaceId = workspaceId,
            title = "AI Command: $truncatedCmd",
            command = command,
            type = WorkspaceProcessType.AI_AGENT_COMMAND,
            status = WorkspaceProcessStatus.RUNNING,
            startTime = System.currentTimeMillis()
        )
        synchronized(_processes) {
            _processes.value = listOf(process) + _processes.value
        }
        return id
    }

    /**
     * Updates an AI command execution when finished.
     */
    fun finishAiCommand(
        processId: String,
        exitCode: Int,
        output: String,
        timedOut: Boolean = false
    ) {
        synchronized(_processes) {
            _processes.value = _processes.value.map { proc ->
                if (proc.id == processId) {
                    val finalStatus = when {
                        timedOut -> WorkspaceProcessStatus.KILLED
                        exitCode == 0 -> WorkspaceProcessStatus.COMPLETED
                        else -> WorkspaceProcessStatus.FAILED
                    }
                    proc.copy(
                        status = finalStatus,
                        exitCode = exitCode,
                        output = output.trim(),
                        endTime = System.currentTimeMillis()
                    )
                } else proc
            }
        }
    }

    fun getProcess(processId: String): WorkspaceProcess? {
        return _processes.value.find { it.id == processId }
    }

    /**
     * Creates a new unique interactive terminal session.
     */
    fun registerNewTerminalSession(workspaceId: String): WorkspaceProcess {
        val index = terminalCounter++
        val process = WorkspaceProcess(
            workspaceId = workspaceId,
            title = "Interactive Bash #$index",
            command = "/bin/bash (PRoot Session)",
            type = WorkspaceProcessType.INTERACTIVE_TERMINAL,
            status = WorkspaceProcessStatus.RUNNING,
            sessionIndex = index,
            startTime = System.currentTimeMillis()
        )
        synchronized(_processes) {
            _processes.value = listOf(process) + _processes.value
        }
        return process
    }

    /**
     * Registers or retrieves an interactive terminal session.
     */
    fun registerTerminalSession(workspaceId: String): WorkspaceProcess {
        val existingActive = _processes.value.find {
            it.workspaceId == workspaceId &&
                    it.type == WorkspaceProcessType.INTERACTIVE_TERMINAL &&
                    it.status == WorkspaceProcessStatus.RUNNING
        }
        if (existingActive != null) return existingActive

        return registerNewTerminalSession(workspaceId)
    }

    /**
     * Closes/terminates an interactive terminal session.
     */
    fun closeTerminalSession(processId: String) {
        synchronized(_processes) {
            _processes.value = _processes.value.map { proc ->
                if (proc.id == processId) {
                    proc.copy(
                        status = WorkspaceProcessStatus.COMPLETED,
                        endTime = System.currentTimeMillis()
                    )
                } else proc
            }
        }
    }

    /**
     * Terminates/kills any running process entry.
     */
    fun killProcess(processId: String) {
        synchronized(_processes) {
            _processes.value = _processes.value.map { proc ->
                if (proc.id == processId && proc.status == WorkspaceProcessStatus.RUNNING) {
                    proc.copy(
                        status = WorkspaceProcessStatus.KILLED,
                        endTime = System.currentTimeMillis()
                    )
                } else proc
            }
        }
    }

    /**
     * Clears completed or failed tasks for a workspace.
     */
    fun clearFinished(workspaceId: String) {
        synchronized(_processes) {
            _processes.value = _processes.value.filter {
                it.workspaceId != workspaceId || it.status == WorkspaceProcessStatus.RUNNING
            }
        }
    }

    /**
     * Deletes a process entry completely from history.
     */
    fun deleteProcess(processId: String) {
        synchronized(_processes) {
            _processes.value = _processes.value.filter { it.id != processId }
        }
    }

    /**
     * Clears all processes for a workspace completely.
     */
    fun clearAll(workspaceId: String) {
        synchronized(_processes) {
            _processes.value = _processes.value.filter { it.workspaceId != workspaceId }
        }
    }
}
