package com.bit.ui.screen.memory

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards
import com.bit.viewmodel.ImportStep
import com.bit.viewmodel.MemoryImportViewModel

val IMPORT_PROMPT_TEXT = """
Hey! I'm exporting my memories and context with you over to another AI assistant. Could you please export all the stored memories, profile details, and preferences you've learned about me from our conversations? Please keep my original phrasing verbatim wherever possible, especially for custom instructions and personal preferences.

## Categories (please organize in this order):

1. **Instructions**: Rules and guidelines I've explicitly asked you to follow — tone, format, style, "always do X", "never do Y", and corrections to your behavior.
2. **Identity**: My name, age, location, education, background, family, relationships, languages, and personal interests.
3. **Career**: Current and past roles, companies, projects, and general skill areas.
4. **Projects**: Projects I meaningfully built or committed to (ideally one entry per project with status, tech stack, and key decisions).
5. **Preferences**: Opinions, tastes, and working-style preferences that apply broadly.

## Format:
Use section headers for each category. Within each category, list entries line-by-line formatted as:
[YYYY-MM-DD] - Entry content here.
(If no date is known, use [unknown] instead.)

## Output:
- Please wrap the complete export inside a single markdown code block so I can easily copy it all at once.
- After the code block, let me know if this is the complete set or if there is more.
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryImportSheets(
    viewModel: MemoryImportViewModel,
    onDismiss: () -> Unit
) {
    val step by viewModel.currentStep.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        when (step) {
            ImportStep.PROMPT -> MemoryImportPromptContent(
                onCopy = {
                    viewModel.setStep(ImportStep.PASTE)
                },
                onSkip = onDismiss,
                onPasteDirectly = { viewModel.setStep(ImportStep.PASTE) }
            )
            ImportStep.PASTE -> MemoryImportPasteContent(
                viewModel = viewModel,
                onBack = { viewModel.setStep(ImportStep.PROMPT) }
            )
            ImportStep.PREVIEW -> MemoryImportPreviewContent(
                viewModel = viewModel,
                onBack = { viewModel.setStep(ImportStep.PASTE) },
                onImport = { viewModel.importSelectedMemories() }
            )
            ImportStep.ERROR -> MemoryImportErrorContent(
                viewModel = viewModel,
                onBack = { viewModel.setStep(ImportStep.PASTE) }
            )
            ImportStep.SUCCESS -> {
                LaunchedEffect(Unit) {
                    onDismiss()
                }
            }
        }
    }
}

@Composable
fun MemoryImportPromptContent(
    onCopy: () -> Unit,
    onSkip: () -> Unit,
    onPasteDirectly: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingLg)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Import Your Memories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Bring context from another AI assistant into BIT",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "Copy this prompt, paste it into ChatGPT or another assistant you've used, then paste its response back here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Read-only prompt block
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 350.dp)
                .background(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(Standards.RadiusMd)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(Standards.RadiusMd)
                )
                .padding(Standards.SpacingMd)
        ) {
            LazyColumn {
                item {
                    Text(
                        text = IMPORT_PROMPT_TEXT,
                        fontFamily = FontFamily.Monospace,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    )
                }
            }
        }

        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Memory Import Prompt", IMPORT_PROMPT_TEXT))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                onCopy()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Standards.RadiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White
            )
        ) {
            Text("Copy Prompt")
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onPasteDirectly,
                shape = RoundedCornerShape(Standards.RadiusMd),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White
                )
            ) {
                Text("Paste Response")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryImportPasteContent(
    viewModel: MemoryImportViewModel,
    onBack: () -> Unit
) {
    val pastedText by viewModel.pastedText.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingLg)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(TnIcons.ArrowLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = "Paste AI Response",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Paste the exact response from your previous AI assistant here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = pastedText,
            onValueChange = { viewModel.updatePastedText(it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 400.dp),
            placeholder = { Text("Paste response here...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
            ),
            shape = RoundedCornerShape(Standards.RadiusMd)
        )

        Button(
            onClick = { viewModel.parsePastedText() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pastedText.isNotBlank(),
            shape = RoundedCornerShape(Standards.RadiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Preview Import")
        }
        
        Spacer(Modifier.height(Standards.SpacingLg))
    }
}

@Composable
fun MemoryImportPreviewContent(
    viewModel: MemoryImportViewModel,
    onBack: () -> Unit,
    onImport: () -> Unit
) {
    val entries by viewModel.parsedEntries.collectAsState()
    val groupedEntries = entries.groupBy { it.category }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingLg)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(TnIcons.ArrowLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                text = "Review Memories",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Select the memories you want to import into your vault.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            groupedEntries.forEach { (category, categoryEntries) ->
                item {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Standards.SpacingSm, bottom = Standards.SpacingXs)
                    )
                }

                items(categoryEntries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(Standards.RadiusSm)
                            )
                            .border(
                                width = 1.dp,
                                color = if (entry.isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(Standards.RadiusSm)
                            )
                            .padding(Standards.SpacingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = entry.isSelected,
                            onCheckedChange = { viewModel.toggleEntrySelection(entry.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(Modifier.width(Standards.SpacingXs))
                        Column {
                            Text(
                                text = entry.dateText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = entry.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        val selectedCount = entries.count { it.isSelected }
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedCount > 0,
            shape = RoundedCornerShape(Standards.RadiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Import $selectedCount Memories")
        }
        
        Spacer(Modifier.height(Standards.SpacingLg))
    }
}

@Composable
fun MemoryImportErrorContent(
    viewModel: MemoryImportViewModel,
    onBack: () -> Unit
) {
    val error by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Standards.SpacingLg),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Standards.SpacingXl))
        
        Icon(
            TnIcons.AlertCircle,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        
        Text(
            text = "Couldn't parse response",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Text(
            text = error ?: "Unknown error occurred.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Standards.RadiusMd),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Go Back")
        }
        
        Spacer(Modifier.height(Standards.SpacingLg))
    }
}
