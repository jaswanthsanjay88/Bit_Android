package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import com.bit.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.data.ModelCategory
import com.bit.models.data.ModelType
import com.bit.ui.components.ActionSwitch
import com.bit.ui.components.ExpandCollapseIcon
import com.bit.utils.SizeCategory
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.viewmodel.SortOption
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards
import com.bit.ui.theme.Glass
import androidx.compose.foundation.shape.RoundedCornerShape

// ── Glass FilterChip shadow wrapper ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = com.bit.ui.theme.LocalBitHaptics.current
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = {
            haptics.selection()
            onClick()
        },
        label = label,
        modifier = modifier,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = Glass.SurfaceSubtle,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            labelColor = Glass.TextSecondary,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            iconColor = Glass.TextSecondary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Glass.BorderSubtle,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        shape = RoundedCornerShape(Standards.ChipCornerRadius)
    )
}

@Composable
fun SearchAppBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCloseSearch,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = TnIcons.ArrowLeft,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = TnIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            androidx.compose.foundation.text.BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search models or repos...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchQueryChange("") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.X,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
    }
}

// ── ModelFiltersSection ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFiltersSection(
    viewModel: ModelStoreViewModel
) {
    val selectedModelType by viewModel.selectedModelType.collectAsStateWithLifecycle()
    val selectedSizeCategory by viewModel.selectedSizeCategory.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingSm)
    ) {
        // Model Type Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Standards.SpacingLg),
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = TnIcons.LayoutGrid,
                contentDescription = null,
                tint = Glass.AccentPrimary,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            FilterChip(
                selected = selectedModelType == null,
                onClick = { viewModel.filterByModelType(null) },
                label = { Text("All Types") }
            )

            FilterChip(
                selected = selectedModelType == ModelType.GGUF,
                onClick = { viewModel.filterByModelType(ModelType.GGUF) },
                label = { Text("LLM (GGUF)") }
            )

            FilterChip(
                selected = selectedModelType == ModelType.SD,
                onClick = { viewModel.filterByModelType(ModelType.SD) },
                label = { Text("Image (SD)") }
            )

            FilterChip(
                selected = selectedModelType == ModelType.TTS,
                onClick = { viewModel.filterByModelType(ModelType.TTS) },
                label = { Text("Speech (TTS)") }
            )

            FilterChip(
                selected = selectedModelType == ModelType.STT,
                onClick = { viewModel.filterByModelType(ModelType.STT) },
                label = { Text("Speech (STT)") }
            )
        }

        Spacer(modifier = Modifier.height(Standards.SpacingSm))

        // Size Category Row (Only shown if modelType is GGUF or null)
        if (selectedModelType == null || selectedModelType == ModelType.GGUF) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Standards.SpacingLg),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TnIcons.Filter,
                    contentDescription = null,
                    tint = Glass.AccentPrimary,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                FilterChip(
                    selected = selectedSizeCategory == null,
                    onClick = { viewModel.filterBySizeCategory(null) },
                    label = { Text("All GGUF Sizes") }
                )

                SizeCategory.entries.forEach { size ->
                    FilterChip(
                        selected = selectedSizeCategory == size,
                        onClick = {
                            viewModel.filterBySizeCategory(
                                if (selectedSizeCategory == size) null else size
                            )
                        },
                        label = { Text(size.displayName) }
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = Standards.SpacingSm),
            color = Glass.BorderSubtle
        )
    }
}
