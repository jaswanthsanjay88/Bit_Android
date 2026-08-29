package com.bit.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.neuron_example.NeuronNode
import com.bit.neuron_example.SourceType
import com.bit.ui.theme.LocalBitHaptics
import kotlin.math.*

/**
 * High-performance 2D Interactive Neural Graph Visualizer.
 * Renders memory chunks and documents as interconnected neural nodes with live pan/zoom.
 */
@Composable
fun NeuronGraphCanvas(
    nodes: List<NeuronNode>,
    selectedNode: NeuronNode?,
    onNodeSelected: (NeuronNode?) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = ""
) {
    val haptics = LocalBitHaptics.current
    val density = LocalDensity.current

    // Viewport transform states
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Pulse animation for neural activity
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )

    // Compute layout positions for nodes
    val nodePositions = remember(nodes) {
        computeNodePositions(nodes)
    }

    val totalConnections = remember(nodes) {
        nodes.sumOf { it.edges.size }
    }

    val matchingNodeIds = remember(nodes, searchQuery) {
        if (searchQuery.isBlank()) emptySet()
        else nodes.filter {
            it.content.contains(searchQuery, ignoreCase = true) ||
            it.metadata.sourceName.contains(searchQuery, ignoreCase = true)
        }.map { it.id }.toSet()
    }

    // Selected node connected IDs for highlighting subgraphs
    val connectedNodeIds = remember(selectedNode) {
        selectedNode?.edges?.map { it.targetId }?.toSet() ?: emptySet()
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(surfaceColor)
    ) {
        if (nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No neural nodes to visualize",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // ── Interactive Canvas ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.35f, 3.5f)
                            offset += pan
                        }
                    }
                    .pointerInput(nodePositions, scale, offset) {
                        detectTapGestures { tapOffset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            var clickedNode: NeuronNode? = null
                            var minDistance = Float.MAX_VALUE

                            nodePositions.forEach { (node, pos) ->
                                val screenPos = center + offset + (pos * scale)
                                val dist = (tapOffset - screenPos).getDistance()
                                val hitRadius = 32.dp.toPx() * scale.coerceAtLeast(0.8f)

                                if (dist <= hitRadius && dist < minDistance) {
                                    minDistance = dist
                                    clickedNode = node
                                }
                            }

                            if (clickedNode != null) {
                                haptics.selection()
                                onNodeSelected(if (clickedNode == selectedNode) null else clickedNode)
                            } else {
                                onNodeSelected(null)
                            }
                        }
                    }
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)

                // 1. Draw Synaptic Connections (Edges)
                nodePositions.forEach { (sourceNode, sourcePos) ->
                    val sourceScreen = center + offset + (sourcePos * scale)

                    sourceNode.edges.forEach { edge ->
                        val targetNode = nodes.find { it.id == edge.targetId }
                        val targetPos = nodePositions[targetNode]
                        if (targetPos != null) {
                            val targetScreen = center + offset + (targetPos * scale)

                            val isHighlighted = selectedNode == null ||
                                    selectedNode.id == sourceNode.id ||
                                    selectedNode.id == edge.targetId

                            val edgeAlpha = if (isHighlighted) {
                                (0.35f + (edge.weight * 0.45f)).coerceIn(0.2f, 0.9f)
                            } else {
                                0.08f
                            }

                            val edgeColor = if (isHighlighted && selectedNode != null) primaryColor else outlineVariant

                            drawLine(
                                color = edgeColor.copy(alpha = edgeAlpha),
                                start = sourceScreen,
                                end = targetScreen,
                                strokeWidth = if (isHighlighted && selectedNode != null) 2.5.dp.toPx() * scale else 1.2.dp.toPx() * scale,
                                cap = StrokeCap.Round
                            )

                            // Draw moving action pulse across highlighted edges
                            if (isHighlighted && selectedNode != null) {
                                val pulseOffset = (sourceScreen * (1f - pulsePhase)) + (targetScreen * pulsePhase)
                                drawCircle(
                                    color = primaryColor,
                                    radius = 3.dp.toPx() * scale,
                                    center = pulseOffset
                                )
                            }
                        }
                    }
                }

                // 2. Draw Neural Nodes
                nodePositions.forEach { (node, pos) ->
                    val screenPos = center + offset + (pos * scale)
                    val isSelected = selectedNode?.id == node.id
                    val isConnected = connectedNodeIds.contains(node.id)
                    val isSearchMatch = matchingNodeIds.contains(node.id)

                    val baseRadius = (if (isSelected) 18.dp else 12.dp).toPx() * scale.coerceIn(0.6f, 1.8f)

                    val nodeColor = when (node.sourceType) {
                        SourceType.TEXT -> primaryColor
                        SourceType.PDF -> secondaryColor
                        SourceType.CHAT -> tertiaryColor
                        SourceType.CUSTOM -> Color(0xFF10B981)
                    }

                    // Pulsing Outer Aura for Selected Node
                    if (isSelected) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.25f * (1f - pulsePhase)),
                            radius = baseRadius + (20.dp.toPx() * pulsePhase * scale),
                            center = screenPos
                        )
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.4f),
                            radius = baseRadius + (6.dp.toPx() * scale),
                            center = screenPos,
                            style = Stroke(width = 2.dp.toPx() * scale)
                        )
                    } else if (isSearchMatch) {
                        drawCircle(
                            color = Color(0xFFF59E0B).copy(alpha = 0.4f),
                            radius = baseRadius + (6.dp.toPx() * scale),
                            center = screenPos,
                            style = Stroke(width = 2.dp.toPx() * scale)
                        )
                    }

                    // Node Core Body
                    drawCircle(
                        color = if (selectedNode != null && !isSelected && !isConnected) nodeColor.copy(alpha = 0.35f) else nodeColor,
                        radius = baseRadius,
                        center = screenPos
                    )

                    // Inner Nucleus Dot
                    drawCircle(
                        color = Color.White.copy(alpha = if (isSelected) 0.9f else 0.6f),
                        radius = baseRadius * 0.35f,
                        center = screenPos
                    )

                    // Node Label (Visible when zoomed in or when selected)
                    if (scale >= 0.85f || isSelected || isSearchMatch) {
                        val label = node.metadata.sourceName.ifBlank { "Node ${node.id.take(4)}" }
                        val paint = android.graphics.Paint().apply {
                            color = if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.GRAY
                            textSize = (10.sp.toPx() * scale.coerceIn(0.7f, 1.3f))
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            label.take(14),
                            screenPos.x,
                            screenPos.y + baseRadius + (12.dp.toPx() * scale),
                            paint
                        )
                    }
                }
            }

            // ── Floating HUD Controls Overlay ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Top-Left Stats Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = primaryColor,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Text(
                            text = "${nodes.size} neurons • $totalConnections synapses",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Bottom-Right Zoom & Reset HUD
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            haptics.pop()
                            scale = (scale * 1.3f).coerceAtMost(3.5f)
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Zoom In", modifier = Modifier.size(18.dp))
                    }

                    FilledTonalIconButton(
                        onClick = {
                            haptics.pop()
                            scale = (scale / 1.3f).coerceAtLeast(0.35f)
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(18.dp))
                    }

                    FilledTonalIconButton(
                        onClick = {
                            haptics.selection()
                            scale = 1f
                            offset = Offset.Zero
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Reset View", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Computes organic, physics-inspired 2D layout coordinates for neural nodes.
 */
private fun computeNodePositions(nodes: List<NeuronNode>): Map<NeuronNode, Offset> {
    if (nodes.isEmpty()) return emptyMap()

    val map = mutableMapOf<NeuronNode, Offset>()
    val count = nodes.size

    // Group nodes by document/source for organic clustering
    val clusters = nodes.groupBy { it.metadata.sourceId.ifBlank { it.metadata.sourceName } }
    val clusterCount = clusters.size

    var clusterIndex = 0
    clusters.forEach { (_, clusterNodes) ->
        val clusterAngle = (clusterIndex.toFloat() / clusterCount) * 2f * PI.toFloat()
        val clusterDistance = if (clusterCount <= 1) 0f else 180f * sqrt(clusterCount.toFloat())
        val clusterCenter = Offset(
            cos(clusterAngle) * clusterDistance,
            sin(clusterAngle) * clusterDistance
        )

        clusterNodes.forEachIndexed { i, node ->
            val nodeAngle = if (clusterNodes.size == 1) 0f else (i.toFloat() / clusterNodes.size) * 2f * PI.toFloat()
            val nodeDist = if (clusterNodes.size == 1) 0f else 75f + (i * 22f)
            val pos = clusterCenter + Offset(
                cos(nodeAngle) * nodeDist,
                sin(nodeAngle) * nodeDist
            )
            map[node] = pos
        }
        clusterIndex++
    }

    return map
}
