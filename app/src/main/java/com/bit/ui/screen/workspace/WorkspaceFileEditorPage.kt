package com.bit.ui.screen.workspace

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bit.ui.theme.LocalBitHaptics
import com.bit.viewmodel.WorkspaceDetailViewModel
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceStorageArea

/**
 * Dedicated full-screen workspace file editor and preview page.
 * Adapted from RikkaHub's WorkspaceFileEditorPage.
 *
 * - FILES area: fully editable with Save action and persistence to disk.
 * - LINUX area: read-only preview mode to prevent accidental rootfs corruption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFileEditorPage(
    workspaceId: String,
    area: WorkspaceStorageArea,
    path: String,
    onBack: () -> Unit,
    viewModel: WorkspaceDetailViewModel = hiltViewModel(),
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    val bitHaptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun loadContent() {
        loading = true
        loadError = null
        scope.launch {
            runCatching {
                viewModel.readText(path, area)
            }.onSuccess { content ->
                textState.setTextAndPlaceCursorAtEnd(content)
                loading = false
            }.onFailure { e ->
                loadError = e.message ?: "Failed to read file"
                loading = false
            }
        }
    }

    LaunchedEffect(workspaceId, area, path) {
        loadContent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (editable) "/workspace/$path" else "/rootfs/$path (Read-Only)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (editable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        bitHaptics.pop()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (editable && !loading && loadError == null) {
                        Button(
                            onClick = {
                                if (saving) return@Button
                                bitHaptics.pop()
                                saving = true
                                scope.launch {
                                    runCatching {
                                        viewModel.writeText(
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        Toast.makeText(context, "Saved $fileName", Toast.LENGTH_SHORT).show()
                                    }.onFailure { e ->
                                        Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading $fileName...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                loadError != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Unable to open file",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = loadError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loadContent() },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        TextField(
                            state = textState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            readOnly = !editable,
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp,
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            )
                        )

                        if (editable) {
                            val codeKeys = listOf(
                                "    " to "TAB",
                                "{" to "{",
                                "}" to "}",
                                "(" to "(",
                                ")" to ")",
                                "[" to "[",
                                "]" to "]",
                                "\"" to "\"",
                                "'" to "'",
                                ":" to ":",
                                ";" to ";",
                                "=" to "=",
                                "<" to "<",
                                ">" to ">",
                                "_" to "_",
                                "+" to "+",
                                "-" to "-",
                                "*" to "*",
                                "/" to "/",
                                "#" to "#"
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    codeKeys.forEach { (insertText, label) ->
                                        FilledTonalButton(
                                            onClick = {
                                                bitHaptics.pop()
                                                textState.edit {
                                                    replace(selection.start, selection.end, insertText)
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                            ),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
