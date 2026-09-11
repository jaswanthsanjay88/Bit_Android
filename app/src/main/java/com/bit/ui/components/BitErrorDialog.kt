package com.bit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bit.global.Standards
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics

/**
 * Expressive Material 3 Error Pop-up Modal.
 * Replaces subtle or hidden snackbars with a polished, actionable dialog.
 */
@Composable
fun BitErrorDialog(
    message: String,
    title: String = "Execution Notice",
    modelName: String? = null,
    onDismiss: () -> Unit,
    onNavigateToModelStore: (() -> Unit)? = null,
    onNavigateToSettings: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val haptics = LocalBitHaptics.current
    val clipboardManager = LocalClipboardManager.current
    var isDetailsExpanded by remember { mutableStateOf(false) }
    var isCopied by remember { mutableStateOf(false) }

    val isModelMissingError = remember(message) {
        val lower = message.lowercase()
        lower.contains("not loaded") || lower.contains("no model") || lower.contains("load a text model")
    }

    val isConfigError = remember(message) {
        val lower = message.lowercase()
        lower.contains("endpoint") || lower.contains("permission") || lower.contains("url not configured")
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: Badge + Title + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TnIcons.AlertCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!modelName.isNullOrBlank()) {
                                Text(
                                    text = modelName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.pop()
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TnIcons.X,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 1.dp
                )

                // Message Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )

                    // Optional Technical Details Toggle if lengthy
                    if (message.length > 90 || message.contains("\n") || message.contains("Exception")) {
                        TextButton(
                            onClick = { isDetailsExpanded = !isDetailsExpanded },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = if (isDetailsExpanded) "Hide Details" else "View Technical Trace",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        AnimatedVisibility(visible = isDetailsExpanded) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = {
                                            clipboardManager.setText(AnnotatedString(message))
                                            isCopied = true
                                            haptics.action()
                                        }) {
                                            Icon(
                                                imageVector = if (isCopied) TnIcons.Check else TnIcons.Copy,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isCopied) "Copied" else "Copy", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Actions Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Secondary context action
                    if (isModelMissingError && onNavigateToModelStore != null) {
                        OutlinedButton(
                            onClick = {
                                haptics.selection()
                                onDismiss()
                                onNavigateToModelStore()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(TnIcons.StoreFront, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Model Store", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (isConfigError && onNavigateToSettings != null) {
                        OutlinedButton(
                            onClick = {
                                haptics.selection()
                                onDismiss()
                                onNavigateToSettings()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(TnIcons.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Settings", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (onRetry != null) {
                        OutlinedButton(
                            onClick = {
                                haptics.action()
                                onDismiss()
                                onRetry()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(TnIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Primary Dismiss button
                    Button(
                        onClick = {
                            haptics.pop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Dismiss", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
