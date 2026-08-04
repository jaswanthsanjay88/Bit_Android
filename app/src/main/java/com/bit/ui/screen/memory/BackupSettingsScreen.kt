package com.bit.ui.screen.memory

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.MemoryVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: MemoryVaultViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    var selectedLocation by remember { mutableStateOf("local") } // "local", "git", "syncthing"
    var selectedSchedule by remember { mutableStateOf("manual") } // "off", "close", "daily", "manual"
    var includeDocuments by remember { mutableStateOf(false) }

    val zipPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importVaultBackup(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Backup & sync",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(TnIcons.ArrowLeft, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Backup Location Section (spec §2.8)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Local ZIP export (Downloads folder)") },
                            trailingContent = { RadioButton(selected = selectedLocation == "local", onClick = { selectedLocation = "local" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Git remote repository") },
                            trailingContent = { RadioButton(selected = selectedLocation == "git", onClick = { selectedLocation = "git" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Syncthing peer sync") },
                            trailingContent = { RadioButton(selected = selectedLocation == "syncthing", onClick = { selectedLocation = "syncthing" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            // Backup Schedule Section (spec §2.8)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Manual only") },
                            trailingContent = { RadioButton(selected = selectedSchedule == "manual", onClick = { selectedSchedule = "manual" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("On app close") },
                            trailingContent = { RadioButton(selected = selectedSchedule == "close", onClick = { selectedSchedule = "close" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        ListItem(
                            headlineContent = { Text("Daily automatic backup") },
                            trailingContent = { RadioButton(selected = selectedSchedule == "daily", onClick = { selectedSchedule = "daily" }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            // Document Toggle Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                ListItem(
                    headlineContent = { Text("Include documents in backup") },
                    supportingContent = { Text("RAG PDFs and source files can increase backup size") },
                    trailingContent = {
                        Switch(checked = includeDocuments, onCheckedChange = { includeDocuments = it })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            // Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.exportVaultBackup() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Icon(TnIcons.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back up now", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { zipPickerLauncher.launch("*/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text("Restore from backup", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
