package com.bit.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.LocalBitHaptics
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Position of item in a group for adaptive corner radius calculation.
 */
enum class ItemPosition {
    ONLY,   // Only item in group - all corners rounded
    FIRST,  // First item - top corners rounded
    MIDDLE, // Middle item - small corners
    LAST    // Last item - bottom corners rounded
}

@Composable
fun PhysicsSwipeToDelete(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    deleteEnabled: Boolean = true,
    position: ItemPosition = ItemPosition.ONLY,
    groupCornerRadius: Dp = 24.dp,
    itemCornerRadius: Dp = 8.dp,
    neighborOffset: Float = 0f,
    onDragProgress: ((offset: Float, isUnlocked: Boolean) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    content: @Composable (shape: Shape) -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    val dragFriction = if (deleteEnabled) 0.6f else 0.15f
    val revealDistancePx = with(density) { 140.dp.toPx() }
    val unlockThresholdPx = revealDistancePx * 0.25f

    val offsetX = remember { Animatable(0f) }
    var isUnlocked by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(deleteEnabled) {
        if (!deleteEnabled) {
            offsetX.snapTo(0f)
            isUnlocked = false
            isDragging = false
        }
    }

    LaunchedEffect(offsetX.value, isUnlocked, isDragging) {
        if (isDragging && !isUnlocked) {
            onDragProgress?.invoke(offsetX.value, isUnlocked)
        }
    }

    val animatedNeighborOffset = remember { Animatable(0f) }
    var wasNeighborInfluenced by remember { mutableStateOf(false) }

    LaunchedEffect(neighborOffset) {
        if (neighborOffset != 0f) {
            animatedNeighborOffset.snapTo(neighborOffset)
            wasNeighborInfluenced = true
        } else if (wasNeighborInfluenced) {
            wasNeighborInfluenced = false
            animatedNeighborOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 1000f)
            )
        }
    }

    val totalOffset = offsetX.value + animatedNeighborOffset.value

    val groupRadiusPx = with(density) { groupCornerRadius.toPx() }
    val itemRadiusPx = with(density) { itemCornerRadius.toPx() }

    val targetTopRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.FIRST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.LAST -> itemRadiusPx
    }
    val targetBottomRadius = when (position) {
        ItemPosition.ONLY, ItemPosition.LAST -> groupRadiusPx
        ItemPosition.MIDDLE, ItemPosition.FIRST -> itemRadiusPx
    }

    val animatedTopRadius by animateFloatAsState(
        targetValue = targetTopRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "topRadius"
    )
    val animatedBottomRadius by animateFloatAsState(
        targetValue = targetBottomRadius,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "bottomRadius"
    )

    val shape by remember {
        derivedStateOf {
            val unlockProgress = (offsetX.value.absoluteValue / unlockThresholdPx).coerceIn(0f, 1f)
            val ownUnlockProgress = if (neighborOffset == 0f) unlockProgress else 0f

            val finalTop = animatedTopRadius + (groupRadiusPx - animatedTopRadius) * ownUnlockProgress
            val finalBottom = animatedBottomRadius + (groupRadiusPx - animatedBottomRadius) * ownUnlockProgress

            RoundedCornerShape(
                topStart = with(density) { finalTop.toDp() },
                topEnd = with(density) { finalTop.toDp() },
                bottomEnd = with(density) { finalBottom.toDp() },
                bottomStart = with(density) { finalBottom.toDp() }
            )
        }
    }

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        val buttonsAlpha = ((totalOffset.absoluteValue - 8f) / (revealDistancePx * 0.4f)).coerceIn(0f, 1f)

        // Swipe action background buttons
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 16.dp)
                .graphicsLayer {
                    alpha = buttonsAlpha
                    translationX = (1f - buttonsAlpha) * 24f
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (deleteEnabled) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(42.dp),
                    onClick = {
                        haptics.pop()
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                            isUnlocked = false
                        }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(42.dp),
                    onClick = {
                        haptics.thud()
                        onDelete()
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Foreground content with physics drag
        Box(
            modifier = Modifier
                .offset { IntOffset(totalOffset.roundToInt(), 0) }
                .pointerInput(deleteEnabled) {
                    if (!deleteEnabled) return@pointerInput

                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                            onDragEnd?.invoke()
                            scope.launch {
                                if (offsetX.value.absoluteValue >= unlockThresholdPx) {
                                    isUnlocked = true
                                    offsetX.animateTo(-revealDistancePx, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                                } else {
                                    isUnlocked = false
                                    offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            onDragEnd?.invoke()
                            scope.launch {
                                offsetX.animateTo(0f)
                                isUnlocked = false
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount * dragFriction).coerceIn(-revealDistancePx * 1.15f, 0f)
                                if (!isUnlocked && newOffset.absoluteValue >= unlockThresholdPx) {
                                    isUnlocked = true
                                    haptics.pop()
                                } else if (isUnlocked && newOffset.absoluteValue < unlockThresholdPx) {
                                    isUnlocked = false
                                    haptics.selection()
                                }
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
        ) {
            content(shape)
        }
    }
}
