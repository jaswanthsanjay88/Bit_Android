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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = Glass.SurfaceSubtle,
            selectedContainerColor = Glass.AccentPrimarySurface,
            labelColor = Glass.TextSecondary,
            selectedLabelColor = Glass.AccentPrimary,
            iconColor = Glass.TextSecondary,
            selectedLeadingIconColor = Glass.AccentPrimary
        ),
        border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Glass.BorderSubtle,
            selectedBorderColor = Glass.AccentPrimary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        shape = RoundedCornerShape(Standards.ChipCornerRadius)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchAppBar(
    searchQuery: String, onSearchQueryChange: (String) -> Unit, onCloseSearch: () -> Unit
) {
    TopAppBar(
        title = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Glass.SurfaceSubtle, RoundedCornerShape(Standards.RadiusMd))
                    .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
                    .padding(horizontal = Standards.SpacingSm),
                contentAlignment = Alignment.CenterStart
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search models...", color = Glass.TextMuted, style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Glass.TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Glass.TextPrimary,
                        unfocusedTextColor = Glass.TextPrimary
                    )
                )
            }
        },
        navigationIcon = {
            com.bit.ui.components.ActionButton(
                onClickListener = onCloseSearch,
                icon = TnIcons.ArrowLeft,
                contentDescription = "Close search",
                modifier = Modifier.padding(start = Standards.SpacingXs)
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = Glass.TextPrimary
        )
    )
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

            // FilterChip(
            //     selected = selectedModelType == ModelType.SD,
            //     onClick = { viewModel.filterByModelType(ModelType.SD) },
            //     label = { Text("Image (SD)") }
            // )

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
