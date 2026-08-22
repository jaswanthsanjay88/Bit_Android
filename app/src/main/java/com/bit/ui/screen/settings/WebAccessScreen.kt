package com.bit.ui.screen.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.network.server.WebAccessManager
import com.bit.ui.components.QrCodeView
import com.bit.ui.theme.LocalBitHaptics

/**
 * LazyListScope extension for Web Access ("BIT in Browser"),
 * seamlessly embedded in SettingsScreen with zero nested scroll conflicts.
 */
fun LazyListScope.webAccessSection(
    webAccessManager: WebAccessManager
) {
    item {
        WebAccessSectionCard(webAccessManager = webAccessManager)
    }
}

@Composable
fun WebAccessSectionCard(
    webAccessManager: WebAccessManager
) {
    val context = LocalContext.current
    val haptics = LocalBitHaptics.current

    val isRunning by webAccessManager.isRunning.collectAsStateWithLifecycle()
    val serverUrl by webAccessManager.serverUrl.collectAsStateWithLifecycle()
    val activePort by webAccessManager.activePort.collectAsStateWithLifecycle()
    val requestLogs by webAccessManager.requestLogs.collectAsStateWithLifecycle()

    var customPort by remember { mutableStateOf(activePort.toString()) }
    var accessPassword by remember { mutableStateOf("") }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    // Permission launcher for Android 13+ foreground service notifications
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val port = customPort.toIntOrNull() ?: 7070
        val ok = webAccessManager.startServer(port)
        if (ok) {
            haptics.success()
            Toast.makeText(context, "BIT Web Server started!", Toast.LENGTH_SHORT).show()
        }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Background Server Access",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "To allow BIT AI to keep the web server running in the background when your screen is turned off or when multitasking, Android requires battery optimization to be disabled for this app.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatteryDialog = false
                        haptics.success()
                        try {
                            val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val fallback = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(fallback)
                            } catch (e2: Exception) {
                                Toast.makeText(context, "Please disable battery optimization in App Info settings", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Allow Background")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text("Later")
                }
            }
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── HERO SERVER CONTROLLER CARD ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = BorderStroke(
                1.dp,
                if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Rounded.Public else Icons.Rounded.PublicOff,
                                    contentDescription = null,
                                    tint = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = if (isRunning) "Web Server Active" else "Web Server Offline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isRunning) "Broadcasting on local network" else "Start server to access chats from PC/Mac",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isRunning,
                        onCheckedChange = { start ->
                            haptics.selection()
                            if (start) {
                                val port = customPort.toIntOrNull() ?: 7070
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    val ok = webAccessManager.startServer(port)
                                    if (ok) {
                                        haptics.success()
                                        Toast.makeText(context, "BIT Web Server started!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        haptics.thud()
                                        Toast.makeText(context, "Failed to bind port $port", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                haptics.thud()
                                webAccessManager.stopServer()
                                Toast.makeText(context, "Web Server stopped", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // Live addresses when running
                if (isRunning) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    val lanUrl = serverUrl.ifEmpty { "http://${webAccessManager.getLocalIpAddress()}:$activePort" }
                    val localhostUrl = "http://localhost:$activePort"

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UrlAddressRow(
                            label = "Local Wi-Fi (LAN)",
                            url = lanUrl,
                            icon = Icons.Rounded.Wifi,
                            onCopy = {
                                haptics.action()
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("BIT LAN URL", lanUrl))
                                Toast.makeText(context, "Copied LAN URL", Toast.LENGTH_SHORT).show()
                            },
                            onOpen = {
                                haptics.pop()
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lanUrl)))
                            }
                        )

                        UrlAddressRow(
                            label = "On-Device",
                            url = localhostUrl,
                            icon = Icons.Rounded.PhoneAndroid,
                            onCopy = {
                                haptics.action()
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("BIT Local URL", localhostUrl))
                                Toast.makeText(context, "Copied Local URL", Toast.LENGTH_SHORT).show()
                            },
                            onOpen = {
                                haptics.pop()
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(localhostUrl)))
                            }
                        )
                    }
                }
            }
        }

        // ── QR CODE CARD ──
        if (isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Scan to Connect",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Point your camera or QR scanner on any laptop, tablet, or smartphone on this Wi-Fi to launch BIT Web App directly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    val lanUrl = serverUrl.ifEmpty { "http://${webAccessManager.getLocalIpAddress()}:$activePort" }
                    QrCodeView(
                        content = lanUrl,
                        size = 110.dp
                    )
                }
            }
        }

        // ── CONFIGURATION SETTINGS ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "SERVER CONFIGURATION",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = customPort,
                    onValueChange = { customPort = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Server Port") },
                    placeholder = { Text("7070") },
                    enabled = !isRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = accessPassword,
                    onValueChange = { accessPassword = it },
                    label = { Text("Access Password (Optional)") },
                    placeholder = { Text("Leave blank for open LAN access") },
                    enabled = !isRunning,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── BATTERY OPTIMIZATION NOTICE ──
        if (!isIgnoringBattery) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep Server Running in Background",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Disable battery optimization to prevent Android from killing the web server while your phone is locked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            haptics.pop()
                            showBatteryDialog = true
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Configure", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Live Server Logs Section ──
            if (isRunning || requestLogs.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Live Server Logs",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${requestLogs.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (requestLogs.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        haptics.pop()
                                        webAccessManager.clearRequestLogs()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Clear",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (requestLogs.isEmpty()) {
                            Text(
                                text = "Waiting for incoming requests... Logs will appear here in real time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                requestLogs.take(15).forEach { log ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = timeFormat.format(java.util.Date(log.timestampMs)),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = when (log.method) {
                                                    "GET" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    "POST" -> Color(0xFF22C55E).copy(alpha = 0.15f)
                                                    "DELETE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                }
                                            ) {
                                                Text(
                                                    text = log.method,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = when (log.method) {
                                                        "GET" -> MaterialTheme.colorScheme.primary
                                                        "POST" -> Color(0xFF22C55E)
                                                        "DELETE" -> MaterialTheme.colorScheme.error
                                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }

                                            Text(
                                                text = log.path,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Text(
                                                text = "${log.status}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = when {
                                                    log.status in 200..299 -> Color(0xFF22C55E)
                                                    log.status in 400..499 -> Color(0xFFEAB308)
                                                    log.status >= 500 -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )

                                            Text(
                                                text = "${log.durationMs}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun UrlAddressRow(
    label: String,
    url: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onCopy: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Open",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
