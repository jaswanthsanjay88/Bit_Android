package com.bit.ui.screen.memory

import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.core.widget.doAfterTextChanged
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    viewModel: MemoryVaultViewModel = hiltViewModel()
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

    // When existingNote updates/loads, sync content & title
    LaunchedEffect(existingNote) {
        if (existingNote != null) {
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
                    currentFilePath = saved.filePath
                    currentNoteId = saved.id
                }
            )
        } else {
            if (currentFilePath.isNotBlank()) {
                val file = File(currentFilePath)
                if (file.exists()) {
                    file.delete()
                    existingNote?.let { viewModel.deleteNote(it) }
                }
            }
        }
    }

    fun insertSnippet(snippet: String, cursorOffset: Int = snippet.length) {
        val view = editTextRef
        if (view != null) {
            val start = view.selectionStart.coerceAtLeast(0)
            val end = view.selectionEnd.coerceAtLeast(0)
            view.text.replace(Math.min(start, end), Math.max(start, end), snippet)
            view.setSelection((Math.min(start, end) + cursorOffset).coerceAtMost(view.text.length))
        } else {
            markdownContent += snippet
        }
        showSlashMenu = false
        performSave()
    }

    fun handleTextChanged(newText: String) {
        markdownContent = newText
        if (newText.endsWith("/") && !showSlashMenu) {
            showSlashMenu = true
        } else if (!newText.endsWith("/") && showSlashMenu) {
            showSlashMenu = false
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
                    IconButton(onClick = { performSave(); onBackClick() }) {
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
                    if (existingNote != null) {
                        IconButton(onClick = {
                            viewModel.deleteNote(existingNote)
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
                Column(modifier = Modifier.weight(1f)) {
                    MarkwonNoteEditor(
                        initialMarkdown = markdownContent,
                        onTextChanged = { handleTextChanged(it) },
                        onViewCreated = { editTextRef = it },
                        textColor = textPrimary,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Material 3 Slash Command Menu Overlay (/ command)
                    AnimatedVisibility(
                        visible = showSlashMenu,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        SlashCommandMenu(
                            onSelect = { snippet ->
                                val view = editTextRef
                                if (view != null && view.text.endsWith("/")) {
                                    val len = view.text.length
                                    view.text.delete(len - 1, len)
                                }
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

/**
 * Markwon Live Syntax-Highlighted Editor wrapper.
 */
@Composable
fun MarkwonNoteEditor(
    initialMarkdown: String,
    onTextChanged: (String) -> Unit,
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
                    onTextChanged(editable?.toString() ?: "")
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

/**
 * Material 3 Expressive Slash Command Menu (/ command picker)
 */
@Composable
private fun SlashCommandMenu(
    onSelect: (String) -> Unit,
    textColor: Color,
    hintColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "MARKDOWN BLOCKS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            SlashOption("Heading 1", "# Large section title") { onSelect("# ") }
            SlashOption("Heading 2", "## Medium section title") { onSelect("## ") }
            SlashOption("Heading 3", "### Small section title") { onSelect("### ") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

            SlashOption("To-do list", "- [ ] Checklist item") { onSelect("- [ ] ") }
            SlashOption("Bulleted list", "- Bulleted point") { onSelect("- ") }
            SlashOption("Numbered list", "1. Numbered item") { onSelect("1. ") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))

            SlashOption("Quote", "> Block quote") { onSelect("> ") }
            SlashOption("Callout", "> [!NOTE] Highlight callout box") { onSelect("> [!NOTE] ") }
            SlashOption("Code Block", "``` Code snippet block") { onSelect("```\n\n```") }
            SlashOption("LaTeX Math Block", "$$ Display equation block") { onSelect("$$\n\n$$") }
            SlashOption("Divider", "--- Horizontal divider line") { onSelect("\n---\n") }
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
