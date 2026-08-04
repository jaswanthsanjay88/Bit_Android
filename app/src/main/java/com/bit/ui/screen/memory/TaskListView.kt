package com.bit.ui.screen.memory

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.table_schema.MemoryNote
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.MemoryVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListView(
    onBackClick: () -> Unit,
    onNoteClick: (noteId: String?, defaultType: String) -> Unit,
    viewModel: MemoryVaultViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val tasks = remember(allNotes) { allNotes.filter { it.noteType == "task" } }

    var isBoardView by remember { mutableStateOf(false) }
    var newTaskTitle by remember { mutableStateOf("") }
    var isDoneCollapsed by remember { mutableStateOf(true) }

    val todoTasks = remember(tasks) { tasks.filter { it.status == "todo" } }
    val doingTasks = remember(tasks) { tasks.filter { it.status == "doing" || it.status == "in_progress" } }
    val doneTasks = remember(tasks) { tasks.filter { it.status == "done" } }

    fun addNewTask() {
        if (newTaskTitle.isNotBlank()) {
            viewModel.saveNote(
                title = newTaskTitle.trim(),
                content = "",
                tags = "task",
                noteType = "task",
                folder = "notes",
                status = "todo"
            )
            newTaskTitle = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(TnIcons.ArrowLeft, contentDescription = "Back")
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = !isBoardView,
                            onClick = { isBoardView = false },
                            label = { Text("List") }
                        )
                        FilterChip(
                            selected = isBoardView,
                            onClick = { isBoardView = true },
                            label = { Text("Board") }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Persistent New Task Input Field (spec §2.6)
            OutlinedTextField(
                value = newTaskTitle,
                onValueChange = { newTaskTitle = it },
                placeholder = { Text("Add a new task...") },
                trailingIcon = {
                    if (newTaskTitle.isNotBlank()) {
                        IconButton(onClick = { addNewTask() }) {
                            Icon(TnIcons.Plus, contentDescription = "Add")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )

            if (!isBoardView) {
                // List View (grouped by todo, doing, done - spec §2.6)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (todoTasks.isNotEmpty()) {
                        item {
                            Text("To Do (${todoTasks.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                        }
                        items(todoTasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                onClick = { onNoteClick(task.id, "task") },
                                onStatusChange = { newStatus ->
                                    viewModel.updateTaskStatus(task, newStatus)
                                    if (newStatus == "done") {
                                        Toast.makeText(context, "Task completed!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }

                    if (doingTasks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("In Progress (${doingTasks.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        items(doingTasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                onClick = { onNoteClick(task.id, "task") },
                                onStatusChange = { newStatus ->
                                    viewModel.updateTaskStatus(task, newStatus)
                                    if (newStatus == "done") {
                                        Toast.makeText(context, "Task completed!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }

                    if (doneTasks.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isDoneCollapsed = !isDoneCollapsed },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Done (${doneTasks.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                                Icon(
                                    imageVector = if (isDoneCollapsed) TnIcons.ArrowLeft else TnIcons.Menu,
                                    contentDescription = "Toggle done",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        if (!isDoneCollapsed) {
                            items(doneTasks, key = { it.id }) { task ->
                                TaskRow(
                                    task = task,
                                    onClick = { onNoteClick(task.id, "task") },
                                    onStatusChange = { newStatus ->
                                        viewModel.updateTaskStatus(task, newStatus)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // Notion Board View (3 Columns: todo / doing / done - spec §2.6)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BoardColumn("To Do", todoTasks, Modifier.weight(1f)) { task ->
                        viewModel.updateTaskStatus(task, "doing")
                    }
                    BoardColumn("Doing", doingTasks, Modifier.weight(1f)) { task ->
                        viewModel.updateTaskStatus(task, "done")
                        Toast.makeText(context, "Task completed!", Toast.LENGTH_SHORT).show()
                    }
                    BoardColumn("Done", doneTasks, Modifier.weight(1f)) { task ->
                        viewModel.updateTaskStatus(task, "todo")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: MemoryNote,
    onClick: () -> Unit,
    onStatusChange: (String) -> Unit
) {
    val isDone = task.status == "done"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { checked ->
                    onStatusChange(if (checked) "done" else "todo")
                }
            )

            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
                ),
                fontWeight = FontWeight.SemiBold,
                color = if (isDone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (task.dueDate != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = "Due",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BoardColumn(
    title: String,
    tasks: List<MemoryNote>,
    modifier: Modifier = Modifier,
    onMoveTask: (MemoryNote) -> Unit
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMoveTask(task) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
