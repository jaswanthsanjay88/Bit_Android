package com.bit.ui.screen.workspace

import android.content.Context
import com.bit.workspace.WorkspaceProcessManager
import com.termux.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap

internal data class TerminalSessionHolder(
    val processId: String,
    val workspaceId: String,
    val session: TerminalSession,
    val sessionClient: WorkspaceTerminalSessionClient,
    val viewClient: WorkspaceTerminalViewClient,
    val sessionIndex: Int,
    val title: String
)

internal object WorkspaceTerminalSessionPool {
    private val sessions = ConcurrentHashMap<String, TerminalSessionHolder>()

    fun getSession(processId: String): TerminalSessionHolder? {
        val holder = sessions[processId]
        if (holder != null && holder.session.isRunning) {
            return holder
        }
        return null
    }

    fun getOrCreateSession(
        context: Context,
        workspaceId: String,
        workspaceRoot: String,
        targetProcessId: String? = null,
        forceNew: Boolean = false,
        onSessionFinished: (String) -> Unit = {}
    ): TerminalSessionHolder? {
        // 1. If targetProcessId is specified and already active in the pool
        if (!forceNew && targetProcessId != null) {
            val existing = sessions[targetProcessId]
            if (existing != null && existing.session.isRunning) {
                return existing
            }
        }

        // 2. If not forcing new and targetProcessId is null, check for any running session in this workspace
        if (!forceNew && targetProcessId == null) {
            val active = sessions.values.find {
                it.workspaceId == workspaceId && it.session.isRunning
            }
            if (active != null) {
                return active
            }
        }

        // 3. Obtain or register process in WorkspaceProcessManager
        val proc = if (targetProcessId != null && !forceNew) {
            WorkspaceProcessManager.getProcess(targetProcessId)
                ?: WorkspaceProcessManager.registerNewTerminalSession(workspaceId)
        } else if (forceNew) {
            WorkspaceProcessManager.registerNewTerminalSession(workspaceId)
        } else {
            WorkspaceProcessManager.registerTerminalSession(workspaceId)
        }

        val launch = prepareWorkspaceTerminalSession(context, workspaceRoot) ?: return null

        lateinit var sessionClient: WorkspaceTerminalSessionClient
        sessionClient = WorkspaceTerminalSessionClient(context, onFinished = {
            WorkspaceProcessManager.closeTerminalSession(proc.id)
            sessions.remove(proc.id)
            onSessionFinished(proc.id)
        })
        val viewClient = WorkspaceTerminalViewClient(context)

        val session = createWorkspaceTerminalSession(context, workspaceRoot, launch, sessionClient)

        val holder = TerminalSessionHolder(
            processId = proc.id,
            workspaceId = workspaceId,
            session = session,
            sessionClient = sessionClient,
            viewClient = viewClient,
            sessionIndex = proc.sessionIndex,
            title = proc.title
        )
        sessions[proc.id] = holder
        return holder
    }

    fun getActiveHoldersForWorkspace(workspaceId: String): List<TerminalSessionHolder> {
        return sessions.values
            .filter { it.workspaceId == workspaceId && it.session.isRunning }
            .sortedBy { it.sessionIndex }
    }

    fun closeSession(processId: String) {
        val holder = sessions.remove(processId)
        holder?.session?.finishIfRunning()
        WorkspaceProcessManager.closeTerminalSession(processId)
    }

    fun killSession(processId: String) {
        val holder = sessions.remove(processId)
        holder?.session?.finishIfRunning()
        WorkspaceProcessManager.killProcess(processId)
    }

    fun deleteSession(processId: String) {
        val holder = sessions.remove(processId)
        holder?.session?.finishIfRunning()
        WorkspaceProcessManager.deleteProcess(processId)
    }

    fun deleteAllForWorkspace(workspaceId: String) {
        sessions.values.filter { it.workspaceId == workspaceId }.forEach { holder ->
            sessions.remove(holder.processId)
            holder.session.finishIfRunning()
        }
        WorkspaceProcessManager.clearAll(workspaceId)
    }
}
