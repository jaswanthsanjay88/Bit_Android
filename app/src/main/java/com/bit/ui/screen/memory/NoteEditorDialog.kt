package com.bit.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.models.table_schema.MemoryNote
import com.bit.ui.icons.TnIcons

@Composable
fun NoteEditorDialog(
    initialNote: MemoryNote? = null,
    defaultType: String = "note",
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, tags: String, noteType: String, status: String, isAiMemoryEnabled: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var content by remember { mutableStateOf(initialNote?.content ?: "") }
    var tags by remember { mutableStateOf(initialNote?.tags ?: "") }
    var noteType by remember { mutableStateOf(initialNote?.noteType ?: defaultType) }
    var status by remember { mutableStateOf(initialNote?.status ?: "todo") }
    var isAiMemoryEnabled by remember { mutableStateOf(initialNote?.isAiMemoryEnabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialNote == null) (if (noteType == "task") "New Notion Task" else "New Obsidian Note") else "Edit Entry",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Type selector chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = noteType == "note",
                        onClick = { noteType = "note" },
                        label = { Text("Markdown Note") }
                    )
                    FilterChip(
                        selected = noteType == "task",
                        onClick = { noteType = "task" },
                        label = { Text("Task / To-Do") }
                    )
                }

                // Title Field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (noteType == "task") "Task Name" else "Note Title") },
                    placeholder = { Text(if (noteType == "task") "e.g., Complete UI overhaul" else "e.g., [[Architecture Rules]]") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Task Status selector if task
                if (noteType == "task") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Status:", style = MaterialTheme.typography.labelMedium)
                        FilterChip(
                            selected = status == "todo",
                            onClick = { status = "todo" },
                            label = { Text("To Do") }
                        )
                        FilterChip(
                            selected = status == "in_progress",
                            onClick = { status = "in_progress" },
                            label = { Text("In Progress") }
                        )
                        FilterChip(
                            selected = status == "done",
                            onClick = { status = "done" },
                            label = { Text("Done") }
                        )
                    }
                }

                // Tags Field
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") },
                    placeholder = { Text("work, preferences, docs") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // AI Memory Toggle
                ListItem(
                    headlineContent = {
                        Text(
                            text = "Remember in AI Memory",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "AI will use this item during conversations",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = TnIcons.Brain,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = isAiMemoryEnabled,
                            onCheckedChange = { isAiMemoryEnabled = it }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )

                // Content Markdown Editor Field
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content / Details (supports [[wikilinks]])") },
                    placeholder = { Text("Write markdown details or use [[wikilinks]] to link notes...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() || content.isNotBlank()) {
                        onSave(title.ifBlank { "Untitled Note" }, content, tags, noteType, status, isAiMemoryEnabled)
                    }
                },
                enabled = title.isNotBlank() || content.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

