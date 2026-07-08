import re
import sys

file_path = "app/src/main/java/com/bit/ui/screen/home/HomeBottomBar.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Replace isPlusExpanded with showAttachmentSheet
content = content.replace("var isPlusExpanded by remember { mutableStateOf(false) }", "var showAttachmentSheet by remember { mutableStateOf(false) }")
content = content.replace("isPlusExpanded = false", "showAttachmentSheet = false")

# 2. Add the AddAttachmentBottomSheet call right after MemoryOverlayBottomSheet
sheet_call = """    // Add Attachment Sheet
    AddAttachmentBottomSheet(
        show = showAttachmentSheet,
        onDismiss = { showAttachmentSheet = false },
        onModelClick = {
            showAttachmentSheet = false
            if (config.showModelList) {
                chatViewModel.hideModelList()
            } else {
                chatViewModel.showModelList()
            }
        },
        onGalleryClick = {
            galleryLauncher.launch("image/*")
        },
        onFilesClick = {
            fileLauncher.launch("*/*")
        },
        toolCallingEnabled = toolCallingEnabled,
        isWebSearchEnabled = isWebSearchEnabled,
        onWebSearchToggle = { pluginViewModel.toggleWebSearch(!isWebSearchEnabled) },
        isMemoryEnabled = isMemoryEnabled,
        onMemoryClick = { memoryViewModel.toggleMemoryOverlay() },
        isRagEnabled = isRagEnabledForChat && loadedRags.isNotEmpty(),
        onRagClick = {
            if (loadedRags.isEmpty()) {
                context.startActivity(Intent(context, RagActivity::class.java))
            } else {
                ragViewModel.toggleRagForChat(!isRagEnabledForChat)
            }
        },
        activePluginCount = enabledPluginNames.count { it != "Web Search" },
        onPluginClick = { pluginViewModel.showPluginOverlay() },
        isThinkingEnabled = chatState.thinkingEnabled,
        onThinkingToggle = { chatViewModel.setThinkingMode(!chatState.thinkingEnabled) }
    )

"""
if "AddAttachmentBottomSheet(" not in content:
    content = content.replace("    Column(\n        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier)\n    ) {", sheet_call + "    Column(\n        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier)\n    ) {")

# 3. Replace the inline row
# We will use regex to find the Row block with ActionButton and AnimatedVisibility, and replace it.
pattern = re.compile(r'Row\(\s*verticalAlignment = Alignment\.CenterVertically,\s*horizontalArrangement = Arrangement\.spacedBy\(Standards\.SpacingSm\)\s*\)\s*\{\s*ActionButton\(\s*onClickListener = \{\s*isPlusExpanded = !isPlusExpanded.*?(?=\s*// Capsule Input Bar)', re.DOTALL)

replacement = """Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                        ) {
                            ActionButton(
                                onClickListener = {
                                    showAttachmentSheet = true
                                },
                                icon = TnIcons.Plus,
                                contentDescription = "Expand Options",
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0x22FFFFFF), // 13% transparent white circle
                                    contentColor = Glass.AccentPrimary
                                )
                            )

                            if (toolCallingEnabled) {
                                // Web Search chip (Primary Toggle)
                                GlassChip(
                                    text = "",
                                    icon = TnIcons.World,
                                    isActive = isWebSearchEnabled,
                                    activeColor = Glass.AccentSecondary,
                                    onClick = { pluginViewModel.toggleWebSearch(!isWebSearchEnabled) },
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }
"""
if pattern.search(content):
    content = pattern.sub(replacement, content)
else:
    print("Could not find inline row pattern.")

# 4. Append GridActionButton and ToggleRow at the end of the file, along with AddAttachmentBottomSheet definition
components = """
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddAttachmentBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onModelClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFilesClick: () -> Unit,
    toolCallingEnabled: Boolean,
    isWebSearchEnabled: Boolean,
    onWebSearchToggle: (Boolean) -> Unit,
    isMemoryEnabled: Boolean,
    onMemoryClick: () -> Unit,
    isRagEnabled: Boolean,
    onRagClick: () -> Unit,
    activePluginCount: Int,
    onPluginClick: () -> Unit,
    isThinkingEnabled: Boolean,
    onThinkingToggle: (Boolean) -> Unit
) {
    if (show) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
                // Header row: X + centered title
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.IconButton(onClick = onDismiss, Modifier.align(Alignment.CenterStart)) {
                        Icon(TnIcons.X, null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text("Add to chat", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.height(20.dp))

                // Zone 1: instant-action grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GridActionButton(icon = TnIcons.Photo, label = "Photos", onClick = onGalleryClick)
                    GridActionButton(icon = TnIcons.Folder, label = "Files", onClick = onFilesClick)
                    GridActionButton(icon = TnIcons.BrainCircuit, label = "Models", onClick = onModelClick)
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                // Zone 2: persistent toggles
                if (toolCallingEnabled) {
                    ToggleRow(icon = TnIcons.World, title = "Web search", checked = isWebSearchEnabled, onCheckedChange = onWebSearchToggle)
                }
                ToggleRow(icon = TnIcons.Database, title = "Connectors", subtitle = if (isRagEnabled) "On" else "Off", onClick = onRagClick)
                if (toolCallingEnabled) {
                    ToggleRow(icon = TnIcons.Wrench, title = "Tool access", subtitle = if (activePluginCount > 0) "$activePluginCount active" else "Auto", onClick = onPluginClick)
                }
                ToggleRow(icon = TnIcons.Brain, title = "Memory", subtitle = if (isMemoryEnabled) "On" else "Off", onClick = onMemoryClick)
                ToggleRow(icon = TnIcons.BulbFilled, title = "Reasoning", checked = isThinkingEnabled, onCheckedChange = onThinkingToggle)
            }
        }
    }
}

@Composable
private fun GridActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Surface(
            onClick = onClick,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    androidx.compose.material3.ListItem(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
        leadingContent = {
            Icon(imageVector = icon, contentDescription = title, tint = MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        },
        trailingContent = {
            if (checked != null && onCheckedChange != null) {
                androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
            } else if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(imageVector = TnIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
"""
if "AddAttachmentBottomSheet(" not in content[-5000:]: # check if already added
    content += components

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done.")
