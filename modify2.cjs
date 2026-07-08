const fs = require('fs');

const file = 'app/src/main/java/com/bit/ui/screen/home/HomeBottomBar.kt';
let content = fs.readFileSync(file, 'utf8');

// 1. Variable rename
content = content.replace('var isPlusExpanded by remember { mutableStateOf(false) }', 'var showAttachmentSheet by remember { mutableStateOf(false) }');
content = content.replace(/isPlusExpanded = false/g, 'showAttachmentSheet = false');
content = content.replace(/isPlusExpanded = true/g, 'showAttachmentSheet = true');

// 2. Remove the centered feature toggle chips
const chipsStart = content.indexOf('// \u2500\u2500 Centered Feature Toggle Chips');
const editBannerStart = content.indexOf('// \u2500\u2500 Edit prompt banner \u2500\u2500');

if (chipsStart !== -1 && editBannerStart !== -1) {
    content = content.substring(0, chipsStart) + content.substring(editBannerStart);
} else {
    console.log("Could not find chipsStart or editBannerStart");
}

// 3. Replace the input pill tools row
const pillStart = content.indexOf('// \u2500\u2500 Sleek ChatGPT Style Input Pill \u2500\u2500');
const capsuleStart = content.indexOf('// Capsule Input Bar');

if (pillStart !== -1 && capsuleStart !== -1) {
    const originalPillSection = content.substring(pillStart, capsuleStart);
    
    // We want to reconstruct the Sleek ChatGPT Style Input Pill section
    const newPillSection = `// \u2500\u2500 Sleek ChatGPT Style Input Pill \u2500\u2500
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    Row(
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

                    `;
    
    content = content.replace(originalPillSection, newPillSection);
} else {
    console.log("Could not find pillStart or capsuleStart");
}


// 4. Add the bottom sheet invocation and composables
const sheetCall = `
    // Add Attachment Sheet
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
            showAttachmentSheet = false
        },
        onFilesClick = {
            fileLauncher.launch("*/*")
            showAttachmentSheet = false
        },
        toolCallingEnabled = toolCallingEnabled,
        isWebSearchEnabled = isWebSearchEnabled,
        onWebSearchToggle = { pluginViewModel.toggleWebSearch(!isWebSearchEnabled) },
        isMemoryEnabled = isMemoryEnabled,
        onMemoryClick = { memoryViewModel.toggleMemoryOverlay(); showAttachmentSheet = false },
        isRagEnabled = isRagEnabledForChat && loadedRags.isNotEmpty(),
        onRagClick = {
            showAttachmentSheet = false
            if (loadedRags.isEmpty()) {
                context.startActivity(Intent(context, RagActivity::class.java))
            } else {
                ragViewModel.toggleRagForChat(!isRagEnabledForChat)
            }
        },
        activePluginCount = enabledPluginNames.count { it != "Web Search" },
        onPluginClick = { pluginViewModel.showPluginOverlay(); showAttachmentSheet = false },
        isThinkingEnabled = chatState.thinkingEnabled,
        onThinkingToggle = { chatViewModel.setThinkingMode(!chatState.thinkingEnabled) }
    )

    Column(
        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier)
    ) {`;

content = content.replace(`    Column(\n        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier)\n    ) {`, sheetCall);


const components = `
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
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
                // Header row: X + centered title
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(TnIcons.X, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                    }
                    Text("Add to chat", modifier = Modifier.align(Alignment.Center), style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.height(20.dp))

                // Zone 1: instant-action grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GridActionButton(icon = TnIcons.Photo, label = "Photos", onClick = onGalleryClick)
                    GridActionButton(icon = TnIcons.Folder, label = "Files", onClick = onFilesClick)
                    GridActionButton(icon = TnIcons.BrainCircuit, label = "Models", onClick = onModelClick)
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
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
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
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
            Icon(imageVector = icon, contentDescription = title, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        },
        trailingContent = {
            if (checked != null && onCheckedChange != null) {
                androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
            } else if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(imageVector = TnIcons.ChevronRight, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
`;

if (!content.includes('fun AddAttachmentBottomSheet')) {
    content += components;
}

fs.writeFileSync(file, content, 'utf8');
console.log("Success");
