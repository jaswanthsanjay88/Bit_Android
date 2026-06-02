package com.bit.ui.screen.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.global.Standards
import com.bit.R
import com.bit.ui.icons.TnIcons

data class SetupComponent(
    val title: String,
    val description: String,
    val state: ComponentState,
    val onAction: () -> Unit,
    val actionText: String = "Download"
)

sealed class ComponentState {
    object Missing : ComponentState()
    data class Downloading(val progress: Float) : ComponentState()
    object Processing : ComponentState()
    object Ready : ComponentState()
}

@Composable
fun FeatureSetupScreen(
    title: String,
    description: String,
    icon: ImageVector,
    components: List<SetupComponent>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(components) { component ->
                SetupComponentCard(component)
            }
        }
    }
}

@Composable
private fun SetupComponentCard(component: SetupComponent) {
    val borderColor = when (component.state) {
        is ComponentState.Ready -> Color(0xFF2E2E2E)
        is ComponentState.Downloading -> Color.White
        is ComponentState.Processing -> Color.White
        is ComponentState.Missing -> Color(0xFF2E2E2E)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(Standards.CardCornerRadius))
            .background(Color(0xFF121212), RoundedCornerShape(Standards.CardCornerRadius))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = component.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = component.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    lineHeight = 18.sp
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            when (component.state) {
                is ComponentState.Ready -> {
                    Icon(
                        imageVector = TnIcons.CircleCheck,
                        contentDescription = "Ready",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                is ComponentState.Downloading -> {
                    // Handled below with progress bar
                }
                is ComponentState.Processing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
                is ComponentState.Missing -> {
                    Button(
                        onClick = component.onAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = component.actionText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        
        if (component.state is ComponentState.Downloading) {
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { component.state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color.White,
                trackColor = Color(0xFF2E2E2E)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(component.state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        } else if (component.state is ComponentState.Processing) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Processing files...",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}
