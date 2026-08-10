package com.bit.ui.components

import androidx.compose.animation.AnimatedContent
import com.bit.ui.theme.Motion
import com.bit.ui.theme.Glass
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.state.AppState
import com.bit.models.state.getBackgroundColor
import com.bit.models.state.getColor
import com.bit.models.state.getContentColor
import com.bit.models.state.getDisplayText
import com.bit.models.state.getIcon
import com.bit.models.table_schema.Model
import com.bit.state.AppStateManager
import com.bit.ui.icons.TnIcons

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AnimatedTitle(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    onShowDynamicWindow: () -> Unit = {}
) {
    val appState by AppStateManager.appState.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = appState, transitionSpec = {
            fadeIn(Motion.entrance()) togetherWith fadeOut(Motion.entrance())
        }, label = "AppStateTitleAnim"
    ) { state ->
        TitleRow(
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            text = state.getDisplayText(),
            icon = state.getIcon(),
            state = state,
            modifier = modifier.clickable {
                onShowDynamicWindow()
            })
    }
}

fun getShortModelLabel(fullModelName: String): String {
    var name = fullModelName.substringAfterLast('/')
    name = name.substringAfterLast('\\')
    if (name.contains('(')) {
        name = name.substringBefore('(').trim()
    }
    name = name.removeSuffix(".gguf")
        .removeSuffix(".bin")
        .removeSuffix(".onnx")
        .trim()
    if (name.contains(':')) {
        name = name.substringBefore(':')
    }
    if (name.contains('/')) {
        name = name.substringAfterLast('/')
    }
    val parts = name.split('-', '_')
    if (parts.size >= 2) {
        val sizeIndex = parts.indexOfFirst { it.lowercase().contains("b") || it.lowercase().contains("m") }
        if (sizeIndex != -1 && sizeIndex < parts.size) {
            name = parts.take(sizeIndex + 1).joinToString("-")
        }
    }
    return name
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun TitleRow(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    state: AppState
) {
    val iconColor = state.getColor()
    val contentColor = MaterialTheme.colorScheme.onSurface
    val isLoading = state is AppState.LoadingModel

    val collapsedText = remember(text, state) {
        when (state) {
            is AppState.ModelLoaded -> "Ready: ${getShortModelLabel(state.modelName)}"
            is AppState.LoadingModel -> "Loading: ${getShortModelLabel(state.modelName)}"
            is AppState.GeneratingText -> "Generating: ${getShortModelLabel(state.modelName)}"
            is AppState.GeneratingImage -> "Generating: ${getShortModelLabel(state.modelName)}"
            is AppState.GeneratingAudio -> "Generating: ${getShortModelLabel(state.modelName)}"
            else -> text
        }
    }

    with(sharedTransitionScope) {
        Box(modifier = modifier) {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(24.dp),
                border = null,
                modifier = Modifier
                    .height(Standards.ActionIconSize)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Standards.SpacingLg)
                ) {
                    if (isLoading) {
                        LoadingIndicator(
                            modifier = Modifier.size(20.dp),
                            color = iconColor
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = collapsedText,
                        color = contentColor,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Small chevron to show details overlay is tapable
                    Icon(
                        imageVector = TnIcons.ChevronDown,
                        contentDescription = "Show details",
                        tint = contentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ModelListItem(
    modifier: Modifier,
    model: Model,
    isLoaded: Boolean,
    onClickListener: (Model) -> Unit,
    onDeleteListener: ((Model) -> Unit)? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Model") },
            text = { Text("Delete \"${model.modelName}\"? This will remove the model file from storage.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteListener?.invoke(model)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteConfirm = false }
                ) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = modifier, colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) MaterialTheme.colorScheme.primary.copy(0.12f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
        ), shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(Standards.SpacingMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (model.providerType.name == "GGUF") TnIcons.Sparkles
                        else TnIcons.Photo,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.modelName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = model.providerType.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDeleteListener != null && !isLoaded) {
                    ActionButton(
                        onClickListener = { showDeleteConfirm = true },
                        icon = TnIcons.Trash,
                        contentDescription = "Delete",
                        shape = RoundedCornerShape(Standards.RadiusMd),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(0.12f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    )
                }

                androidx.compose.animation.Crossfade(
                    targetState = isLoaded,
                    label = "button_state"
                ) { loaded ->
                    if (loaded) {
                        ActionTextButton(
                            onClickListener = { onClickListener(model) },
                            icon = TnIcons.CornerDownLeft,
                            text = "Unload",
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(0.12f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(Standards.RadiusMd)
                        )
                    } else {
                        ActionButton(
                            onClickListener = { onClickListener(model) },
                            icon = TnIcons.ExternalLink,
                            contentDescription = "Load",
                            shape = RoundedCornerShape(Standards.RadiusMd),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(0.12f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelList(
    installedModels: List<Model>,
    currentModelID: String,
    onClickListener: (Model) -> Unit,
    modifier: Modifier = Modifier,
    onDeleteListener: ((Model) -> Unit)? = null,
    maxHeight: Dp = 200.dp
) {
    if (installedModels.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(Standards.SpacingLg),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
            ) {
                Icon(
                    imageVector = TnIcons.Photo,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "No models installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            contentPadding = PaddingValues(Standards.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
        ) {
            items(installedModels) { model ->
                ModelListItem(
                    modifier = Modifier.fillMaxWidth(),
                    model = model,
                    isLoaded = currentModelID == model.id,
                    onClickListener = onClickListener,
                    onDeleteListener = onDeleteListener
                )
            }
        }
    }
}
