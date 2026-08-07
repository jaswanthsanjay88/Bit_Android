package com.bit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.models.messages.Messages
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.BitColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionBottomSheet(
    message: Messages,
    show: Boolean,
    onDismiss: () -> Unit,
    onEditRequest: ((Messages) -> Unit)? = null,
    onSaveToMemory: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current

    if (show) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = BitColors.Surface.copy(alpha = 0.92f),
            dragHandle = {
                Box(
                    Modifier
                        .padding(vertical = Standards.SpacingMd)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingMd)
                    .padding(bottom = Standards.SpacingXl),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
            ) {
                Text(
                    text = "Message Actions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (onEditRequest != null) {
                    ActionItem(
                        icon = TnIcons.Edit,
                        text = "Edit Prompt",
                        onClick = {
                            onEditRequest(message)
                            onDismiss()
                        }
                    )
                }

                if (onSaveToMemory != null) {
                    ActionItem(
                        icon = TnIcons.Brain,
                        text = "Save to Memory Vault",
                        onClick = {
                            onSaveToMemory(message.content.content)
                            onDismiss()
                        }
                    )
                }

                ActionItem(
                    icon = TnIcons.Copy,
                    text = "Copy Text",
                    onClick = {
                        clipboardManager.setText(buildAnnotatedString { append(message.content.content) })
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
