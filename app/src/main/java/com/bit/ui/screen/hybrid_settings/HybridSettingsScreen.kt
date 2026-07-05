package com.bit.ui.screen.hybrid_settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.data.AppSettingsDataStore
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.worker.LlmModelWorker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HybridSettingsScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val appSettings = remember { AppSettingsDataStore(context) }

    val localServerEnabled by appSettings.localServerEnabled.collectAsStateWithLifecycle(initialValue = false)
    val localServerPort by appSettings.localServerPort.collectAsStateWithLifecycle(initialValue = 8080)
    val localServerToken by appSettings.localServerToken.collectAsStateWithLifecycle(initialValue = "")

    val isGgufLoaded by LlmModelWorker.isGgufModelLoaded.collectAsStateWithLifecycle()
    val activeGgufModelId by LlmModelWorker.currentGgufModelId.collectAsStateWithLifecycle()

    var portInput by remember(localServerPort) { mutableStateOf(localServerPort.toString()) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Hybrid & Local Server",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary
                    )
                },
                navigationIcon = {
                    ActionButton(
                        onClickListener = onBackClick,
                        icon = TnIcons.ArrowLeft,
                        contentDescription = "Back"
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xCC050505),
                    titleContentColor = Glass.TextPrimary
                )
            )
        },
        containerColor = Color(0xFF050505)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF050505)),
            contentPadding = PaddingValues(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            item {
                Text(
                    text = "Configure BIT to run as a background service and host standard OpenAI-compatible API endpoints for other apps on your device or local network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // --- Section 1: Local HTTP API Server ---
            item {
                Text(
                    text = "Local OpenAI API Server",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Glass.AccentPrimary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                GlassCard(
                    cornerRadius = Standards.RadiusMd,
                    backgroundColor = Glass.Surface,
                    borderColor = Glass.BorderSubtle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Standards.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
                    ) {
                        // Toggle Server
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Host Local OpenAI Server",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Glass.TextPrimary
                               )
                                Text(
                                    "Exposes REST endpoints on 127.0.0.1",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Glass.TextMuted
                                )
                            }
                            Switch(
                                checked = localServerEnabled,
                                onCheckedChange = {
                                    scope.launch {
                                        appSettings.updateLocalServerEnabled(it)
                                        Toast.makeText(
                                            context,
                                            if (it) "Local server configuration saved. (Needs reload)" else "Local server disabled",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Glass.AccentPrimary,
                                    checkedTrackColor = Glass.AccentPrimary.copy(alpha = 0.4f)
                                )
                            )
                        }

                        // Port Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Server Port",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Glass.TextPrimary
                                )
                                Text(
                                    "Port for the local HTTP endpoints",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Glass.TextMuted
                                )
                            }
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() } && input.length <= 5) {
                                        portInput = input
                                        val port = input.toIntOrNull()
                                        if (port != null && port in 1024..65535) {
                                            scope.launch {
                                                appSettings.updateLocalServerPort(port)
                                            }
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(90.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = Glass.TextPrimary,
                                    fontFamily = FontFamily.Monospace
                                ),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Glass.AccentPrimary,
                                    unfocusedBorderColor = Glass.BorderSubtle,
                                    focusedContainerColor = Color(0x0AFFFFFF),
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                        }

                        // Local API Authentication Key
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Local API Token",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Glass.TextPrimary
                            )
                            Text(
                                "Token required in 'Authorization: Bearer <token>' headers",
                                style = MaterialTheme.typography.bodySmall,
                                color = Glass.TextMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .background(Color(0x05FFFFFF), shape = RoundedCornerShape(8.dp))
                                        .border(1.dp, Glass.BorderSubtle, shape = RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = localServerToken.ifEmpty { "No Token Generated" },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        ),
                                        color = if (localServerToken.isEmpty()) Glass.TextMuted else Glass.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                ActionButton(
                                    onClickListener = {
                                        val newToken = UUID.randomUUID().toString().replace("-", "").take(16)
                                        scope.launch {
                                            appSettings.updateLocalServerToken(newToken)
                                            Toast.makeText(context, "New API Token generated!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    icon = TnIcons.Refresh,
                                    contentDescription = "Generate Key"
                                )

                                ActionButton(
                                    onClickListener = {
                                        if (localServerToken.isNotEmpty()) {
                                            clipboardManager.setText(AnnotatedString(localServerToken))
                                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    icon = TnIcons.Copy,
                                    contentDescription = "Copy Token",
                                    enabled = localServerToken.isNotEmpty()
                                )
                            }
                        }
                    }
                }
            }

            // --- Section 2: Process Diagnostics & Health ---
            item {
                Text(
                    text = "Process Diagnostics & Health",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Glass.AccentPrimary,
                    modifier = Modifier.padding(top = Standards.SpacingSm, bottom = 6.dp)
                )

                GlassCard(
                    cornerRadius = Standards.RadiusMd,
                    backgroundColor = Glass.Surface,
                    borderColor = Glass.BorderSubtle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Standards.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpecRow("Inference Isolation", "Active (:inference process)")
                        SpecRow("Model Loaded", if (isGgufLoaded) activeGgufModelId ?: "Yes" else "None Loaded")
                        
                        Divider(color = Glass.BorderSubtle)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Simulate Engine Crash",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Glass.TextPrimary
                                )
                                Text(
                                    "Forces a native process crash to test recovery",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Glass.TextMuted
                                )
                            }
                            Button(
                                onClick = {
                                    try {
                                        LlmModelWorker.simulateEngineCrash()
                                        Toast.makeText(context, "Crash simulation signal sent!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0x19F44336),
                                    contentColor = Color(0xFFF44336)
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33F44336)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Trigger Crash", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Glass.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Glass.TextPrimary)
    }
}

@Composable
private fun Divider(color: Color) {
    androidx.compose.material3.HorizontalDivider(color = color, thickness = 0.8.dp)
}
