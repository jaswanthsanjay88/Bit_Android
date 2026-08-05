package com.bit.ui.screen.memory

import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.widget.doAfterTextChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.ComponentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.table_schema.MemoryNote
import com.bit.ui.components.MarkdownText
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.MemoryVaultViewModel
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import java.io.File
import java.util.UUID

/**
 * Material 3 Expressive Note Detail & Editor Screen.
 *
 * Design Architecture (Material 3 + ponytail):
 * 1. Plain Markdown text is the single source of truth saved to VaultFileStore.
 * 2. Markwon live syntax-highlighted editor via AndroidView & EditText for editing.
 * 3. GFM AST MarkdownText composable for rich Preview/Reading mode.
 * 4. Expressive M3 Slash Command Menu (/ command) with surface container tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: String?,
    defaultType: String = "note",
    onBackClick: () -> Unit,
    viewModel: MemoryVaultViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
) {
    val context = LocalContext.current
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val existingNote = remember(allNotes, noteId) {
        if (!noteId.isNullOrBlank()) allNotes.find { it.id == noteId } else null
    }

    var currentNoteId by remember(existingNote) { mutableStateOf(existingNote?.id) }
    var currentFilePath by remember(existingNote) { mutableStateOf(existingNote?.filePath ?: "") }
    var title by remember(existingNote) { mutableStateOf(existingNote?.title ?: "") }
    var noteType by remember(existingNote) { mutableStateOf(existingNote?.noteType ?: defaultType) }

    var markdownContent by remember(existingNote) { mutableStateOf(existingNote?.content ?: "") }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

    var isPreviewMode by remember { mutableStateOf(false) }
    var showSlashMenu by remember { mutableStateOf(false) }
    var slashQuery by remember { mutableStateOf("") }
    var isDeleted by remember { mutableStateOf(false) }

    // When existingNote updates/loads, sync content & title
    LaunchedEffect(existingNote) {
        if (existingNote != null && !isDeleted) {
            markdownContent = existingNote.content
            title = existingNote.title
            currentFilePath = existingNote.filePath
            currentNoteId = existingNote.id
            editTextRef?.let { view ->
                if (view.text.toString() != existingNote.content) {
                    view.setText(existingNote.content)
                }
            }
        }
    }

    fun performSave() {
        if (isDeleted) return
        val content = markdownContent
        if (title.isNotBlank() || content.isNotBlank()) {
            val idToUse = currentNoteId ?: UUID.randomUUID().toString().also { currentNoteId = it }
            viewModel.saveNote(
                title = title.ifBlank { "Untitled" },
                content = content,
                noteType = noteType,
                existingId = idToUse,
                filePath = currentFilePath,
                onSaved = { saved ->
                    if (!isDeleted) {
                        currentFilePath = saved.filePath
                        currentNoteId = saved.id
                    }
                }
            )
        } else {
            val targetId = currentNoteId ?: existingNote?.id
            if (targetId != null) {
                isDeleted = true
                val noteToDelete = existingNote ?: MemoryNote(
                    id = targetId,
                    title = title,
                    content = content,
                    filePath = currentFilePath
                )
                viewModel.deleteNote(noteToDelete)
            }
        }
    }

    fun insertSnippet(snippet: String, cursorOffset: Int = snippet.length) {
        val view = editTextRef
        if (view != null) {
            val cursor = view.selectionStart.coerceAtLeast(0)
            val textBeforeCursor = view.text.substring(0, cursor.coerceAtMost(view.text.length))
            val lastSlash = textBeforeCursor.lastIndexOf('/')
            if (lastSlash >= 0) {
                view.text.delete(lastSlash, cursor)
            }
            val insertPos = view.selectionStart.coerceAtLeast(0)
            view.text.insert(insertPos, snippet)
            view.setSelection((insertPos + cursorOffset).coerceAtMost(view.text.length))
        } else {
            markdownContent += snippet
        }
        showSlashMenu = false
        slashQuery = ""
        performSave()
    }

    fun handleTextChanged(newText: String, selectionStart: Int) {
        markdownContent = newText

        // Check slash trigger at cursor position
        val cursor = selectionStart.coerceIn(0, newText.length)
        val textBeforeCursor = newText.substring(0, cursor)

        val lastSlash = textBeforeCursor.lastIndexOf('/')
        if (lastSlash >= 0) {
            val wordAfterSlash = textBeforeCursor.substring(lastSlash + 1)
            val isAtStartOrAfterSpace = lastSlash == 0 ||
                    textBeforeCursor[lastSlash - 1] == ' ' ||
                    textBeforeCursor[lastSlash - 1] == '\n' ||
                    textBeforeCursor[lastSlash - 1] == '\r'

            if (isAtStartOrAfterSpace && !wordAfterSlash.contains(" ") && !wordAfterSlash.contains("\n")) {
                slashQuery = wordAfterSlash
                showSlashMenu = true
            } else {
                showSlashMenu = false
                slashQuery = ""
            }
        } else {
            showSlashMenu = false
            slashQuery = ""
        }

        performSave()
    }

    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceBg = MaterialTheme.colorScheme.surface

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { if (!isDeleted) performSave(); onBackClick() }) {
                        Icon(TnIcons.ArrowLeft, contentDescription = "Back", tint = textPrimary)
                    }
                },
                actions = {
                    // Material 3 Edit/Preview Mode FilterChip
                    FilterChip(
                        selected = isPreviewMode,
                        onClick = { isPreviewMode = !isPreviewMode },
                        label = {
                            Text(
                                text = if (isPreviewMode) "Preview" else "Edit",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isPreviewMode) TnIcons.Code else TnIcons.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    // Save Action
                    IconButton(onClick = {
                        performSave()
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(TnIcons.Download, contentDescription = "Save", tint = textSecondary, modifier = Modifier.size(20.dp))
                    }
                    // Delete Action
                    val activeNoteId = currentNoteId ?: existingNote?.id
                    if (activeNoteId != null) {
                        IconButton(onClick = {
                            isDeleted = true
                            val noteToDelete = existingNote ?: MemoryNote(
                                id = activeNoteId,
                                title = title,
                                content = markdownContent,
                                filePath = currentFilePath
                            )
                            viewModel.deleteNote(noteToDelete)
                            onBackClick()
                        }) {
                            Icon(TnIcons.Trash, contentDescription = "Delete", tint = textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = surfaceBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            // Material 3 Title Input
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = title,
                onValueChange = { title = it; performSave() },
                textStyle = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    lineHeight = 36.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            "Untitled",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = textSecondary.copy(alpha = 0.5f)
                            )
                        )
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(16.dp))

            if (isPreviewMode) {
                // Material 3 AST Markdown Preview
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(
                        text = markdownContent.ifBlank { "*No content yet. Tap Edit to start typing.*" },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                // Markwon Live Syntax-Highlighted Editor
                Box(modifier = Modifier.weight(1f)) {
                    MarkwonNoteEditor(
                        initialMarkdown = markdownContent,
                        onTextChanged = { text, selection -> handleTextChanged(text, selection) },
                        onViewCreated = { editTextRef = it },
                        textColor = textPrimary,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Floating Menu above soft keyboard (window adjustResize handles pushing it up)
                    if (showSlashMenu) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        ) {
                            SlashCommandMenu(
                                filterQuery = slashQuery,
                                onSelect = { snippet ->
                                    insertSnippet(snippet)
                                },
                                textColor = textPrimary,
                                hintColor = textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Markwon Live Syntax-Highlighted Editor wrapper.
 */
@Composable
fun MarkwonNoteEditor(
    initialMarkdown: String,
    onTextChanged: (text: String, selectionStart: Int) -> Unit,
    onViewCreated: (EditText) -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }
    val editor = remember(markwon) {
        MarkwonEditor.create(markwon)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setTextColor(textColor.toArgb())
                textSize = 16f
                setHintTextColor(textColor.copy(alpha = 0.45f).toArgb())
                hint = "Type markdown or / for commands…"
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

                setText(initialMarkdown)
                addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))
                doAfterTextChanged { editable ->
                    val text = editable?.toString() ?: ""
                    val sel = selectionStart
                    onTextChanged(text, sel)
                }
                onViewCreated(this)
            }
        },
        update = { view ->
            if (!view.hasFocus() && view.text.toString() != initialMarkdown) {
                view.setText(initialMarkdown)
            }
        }
    )
}

private data class SlashItem(val title: String, val subtitle: String, val snippet: String)

/**
 * Material 3 Expressive Slash Command Menu (/ command picker)
 */
@Composable
private fun SlashCommandMenu(
    filterQuery: String,
    onSelect: (String) -> Unit,
    textColor: Color,
    hintColor: Color
) {
    val allItems = remember {
        listOf(
            SlashItem("Heading 1", "# Large section title", "# "),
            SlashItem("Heading 2", "## Medium section title", "## "),
            SlashItem("Heading 3", "### Small section title", "### "),
            SlashItem("To-do list", "- [ ] Checklist item", "- [ ] "),
            SlashItem("Bulleted list", "- Bulleted point", "- "),
            SlashItem("Numbered list", "1. Numbered item", "1. "),
            SlashItem("Quote", "> Block quote", "> "),
            SlashItem("Callout", "> [!NOTE] Highlight callout box", "> [!NOTE] "),
            SlashItem("Code Block", "``` Code snippet block", "```\n\n```"),
            SlashItem("LaTeX Math Block", "$$ Display equation block", "$$\n\n$$"),
            SlashItem("Divider", "--- Horizontal divider line", "\n---\n")
        )
    }

    val filteredItems = remember(filterQuery) {
        if (filterQuery.isBlank()) {
            allItems
        } else {
            val queryLower = filterQuery.lowercase()
            allItems.filter {
                it.title.lowercase().contains(queryLower) ||
                it.subtitle.lowercase().contains(queryLower) ||
                it.snippet.lowercase().contains(queryLower)
            }
        }
    }

    if (filteredItems.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .heightIn(max = 240.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "COMMANDS (${filteredItems.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                filteredItems.forEach { item ->
                    SlashOption(item.title, item.subtitle) { onSelect(item.snippet) }
                }
            }
        }
    }
}

@Composable
private fun SlashOption(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

