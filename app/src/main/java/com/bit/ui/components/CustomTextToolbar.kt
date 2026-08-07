package com.bit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

data class TextToolbarState(
    val status: TextToolbarStatus = TextToolbarStatus.Hidden,
    val rect: Rect = Rect.Zero,
    val onCopyRequested: (() -> Unit)? = null,
    val onSelectAllRequested: (() -> Unit)? = null,
    val onPasteRequested: (() -> Unit)? = null,
    val onCutRequested: (() -> Unit)? = null
)

class CustomTextToolbar(
    private val onStateChanged: (TextToolbarState) -> Unit
) : TextToolbar {
    private var _status = TextToolbarStatus.Hidden
    override val status: TextToolbarStatus get() = _status

    override fun hide() {
        _status = TextToolbarStatus.Hidden
        onStateChanged(TextToolbarState(status = _status))
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        _status = TextToolbarStatus.Shown
        onStateChanged(
            TextToolbarState(
                status = _status,
                rect = rect,
                onCopyRequested = onCopyRequested,
                onSelectAllRequested = onSelectAllRequested,
                onPasteRequested = onPasteRequested,
                onCutRequested = onCutRequested
            )
        )
    }
}

@Composable
fun CustomTextSelectionPopup(
    state: TextToolbarState,
    onDismiss: () -> Unit
) {
    if (state.status == TextToolbarStatus.Shown) {
        // We'll place it floating near the top center of the screen to ensure it's always visible
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, 150),
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
        ) {
            GlassCard(
                modifier = Modifier.padding(8.dp),
                cornerRadius = 24.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.onCopyRequested != null) {
                        TextButton(onClick = { 
                            state.onCopyRequested.invoke()
                            onDismiss()
                        }) {
                            Text("Copy", color = Color.White)
                        }
                    }
                    if (state.onSelectAllRequested != null) {
                        TextButton(onClick = { 
                            state.onSelectAllRequested.invoke()
                        }) {
                            Text("Select All", color = Color.White)
                        }
                    }
                    if (state.onPasteRequested != null) {
                        TextButton(onClick = { 
                            state.onPasteRequested.invoke()
                            onDismiss()
                        }) {
                            Text("Paste", color = Color.White)
                        }
                    }
                    if (state.onCutRequested != null) {
                        TextButton(onClick = { 
                            state.onCutRequested.invoke()
                            onDismiss()
                        }) {
                            Text("Cut", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
