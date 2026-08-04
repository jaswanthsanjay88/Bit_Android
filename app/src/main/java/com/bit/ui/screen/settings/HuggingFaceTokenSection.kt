package com.bit.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.bit.global.Standards
import com.bit.ui.components.CaptionText
import com.bit.ui.components.PasswordTextField
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.StatusBadge
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.R

// ── State types ──

enum class HfTokenState { SET, NOT_SET, SAVING }

sealed class HfTestResult {
    data class Success(val username: String) : HfTestResult()
    data class Failed(val error: String) : HfTestResult()
    data object Testing : HfTestResult()
}

// ── LazyList section builder ──

fun LazyListScope.huggingFaceTokenSection(
    tokenState: HfTokenState,
    testResult: HfTestResult?,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onTestConnection: () -> Unit
) {
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
    item {
        GlassSectionCard(
            title = "HuggingFace Access",
            icon = TnIcons.HuggingFace,
            iconTint = Color.Unspecified,
            description = "Authenticate to download gated model resources (e.g. Llama, Gemma)"
        ) {
            HuggingFaceTokenContent(
                tokenState = tokenState,
                testResult = testResult,
                onSaveToken = onSaveToken,
                onClearToken = onClearToken,
                onTestConnection = onTestConnection
            )
        }
    }
}

// ── Main Content Composable ──

@Composable
private fun HuggingFaceTokenContent(
    tokenState: HfTokenState,
    testResult: HfTestResult?,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onTestConnection: () -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
        // Status badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Connection Status",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Glass.TextPrimary
            )
            StatusBadge(
                text = when (tokenState) {
                    HfTokenState.SET -> "Connected"
                    HfTokenState.NOT_SET -> "Not Set"
                    HfTokenState.SAVING -> "Saving…"
                },
                isActive = tokenState == HfTokenState.SET
            )
        }

        // Password field with eye toggle
        PasswordTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Access Token (hf_...)"
        )

        // Action buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Save button
            Button(
                onClick = {
                    onSaveToken(tokenInput)
                    tokenInput = ""
                },
                enabled = tokenInput.isNotBlank() && tokenState != HfTokenState.SAVING,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    TnIcons.DeviceFloppy,
                    contentDescription = null,
                    modifier = Modifier.size(Standards.IconSm)
                )
                Text(
                    text = "Save",
                    modifier = Modifier.padding(start = Standards.SpacingXs),
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Test button (only when token is set)
            AnimatedVisibility(
                visible = tokenState == HfTokenState.SET,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TextButton(onClick = onTestConnection) {
                    Text("Test", fontWeight = FontWeight.Medium)
                }
            }

            // Clear button (only when token is set)
            AnimatedVisibility(
                visible = tokenState == HfTokenState.SET,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TextButton(onClick = onClearToken) {
                    Text(
                        "Clear",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Test result feedback
        AnimatedVisibility(visible = testResult != null) {
            testResult?.let { result ->
                Column(modifier = Modifier.padding(top = Standards.SpacingXxs)) {
                    StatusBadge(
                        text = when (result) {
                            is HfTestResult.Success -> "✓ Authenticated as ${result.username}"
                            is HfTestResult.Failed -> "✗ ${result.error}"
                            is HfTestResult.Testing -> "Testing connection…"
                        },
                        isActive = result is HfTestResult.Success
                    )
                }
            }
        }

        // Help caption
        CaptionText(
            text = "Get your token at huggingface.co/settings/tokens. Required for gated models like Llama, Gemma, etc."
        )
    }
}
