package com.bit.ui.screen.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bit.models.table_schema.WorkspaceEntity

/**
 * Top-level Host Screen for Linux PRoot Workspaces.
 * Coordinates workspace listing, folder drill-down, file editing, and interactive PRoot terminal.
 */
@Composable
fun WorkspaceHostScreen(
    onBack: () -> Unit
) {
    var detailWorkspaceId by remember { mutableStateOf<String?>(null) }
    var terminalWorkspace by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var targetTerminalProcessId by remember { mutableStateOf<String?>(null) }
    var isEditingWorkspaceFile by remember { mutableStateOf(false) }

    val currentSubScreen = when {
        terminalWorkspace != null -> "terminal"
        detailWorkspaceId != null -> "detail"
        else -> "list"
    }

    // Handle Android system back gesture cleanly
    BackHandler {
        when (currentSubScreen) {
            "terminal" -> {
                terminalWorkspace = null
                targetTerminalProcessId = null
            }
            "detail" -> {
                if (!isEditingWorkspaceFile) {
                    detailWorkspaceId = null
                }
            }
            else -> onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentSubScreen,
            transitionSpec = {
                if (targetState == "terminal" || (targetState == "detail" && initialState == "list")) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 3 } + fadeOut()
                } else {
                    slideInHorizontally { -it / 3 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            label = "workspace_host_navigation"
        ) { subScreen ->
            when (subScreen) {
                "terminal" -> terminalWorkspace?.let { ws ->
                    WorkspaceTerminalPage(
                        workspace = ws,
                        targetProcessId = targetTerminalProcessId,
                        onBack = {
                            terminalWorkspace = null
                            targetTerminalProcessId = null
                        }
                    )
                }
                "detail" -> detailWorkspaceId?.let { id ->
                    WorkspaceDetailPage(
                        workspaceId = id,
                        onBack = {
                            detailWorkspaceId = null
                            isEditingWorkspaceFile = false
                        },
                        onOpenTerminal = { ws, procId ->
                            terminalWorkspace = ws
                            targetTerminalProcessId = procId
                        },
                        onEditingFileChanged = { isEditing ->
                            isEditingWorkspaceFile = isEditing
                        }
                    )
                }
                else -> {
                    WorkspacePage(
                        onBack = onBack,
                        onOpenWorkspace = { ws -> detailWorkspaceId = ws.id },
                        onOpenTerminal = { ws ->
                            terminalWorkspace = ws
                            targetTerminalProcessId = null
                        }
                    )
                }
            }
        }
    }
}
