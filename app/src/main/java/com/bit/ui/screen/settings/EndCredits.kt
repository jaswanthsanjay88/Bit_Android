package com.bit.ui.screen.settings

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

sealed class CreditLine {
    data class Heading(val text: String) : CreditLine()
    data class Role(val text: String) : CreditLine()
    data class Name(val text: String) : CreditLine()
    data class Space(val heightDp: Int = 48) : CreditLine()
}

@Composable
fun EndCreditsOverlay(
    audioResIds: List<Int>,
    lines: List<CreditLine>,
    onDismiss: () -> Unit,
    scrollDurationMs: Int = 26000
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(true) }
    val animatedScroll by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(scrollDurationMs, easing = LinearEasing),
        label = "creditsScroll"
    )

    // Select a random song from the list
    val selectedAudioResId = remember { audioResIds.random() }

    // --- audio playback lifecycle ---
    val mediaPlayer = remember {
        MediaPlayer.create(context, selectedAudioResId)?.apply {
            isLooping = false
            setVolume(0f, 0f)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
    }

    LaunchedEffect(Unit) {
        mediaPlayer?.start()
        for (i in 0..10) {
            mediaPlayer?.setVolume(i / 10f, i / 10f)
            delay(60)
        }
    }

    // auto-dismiss shortly after the scroll (and song) finishes
    LaunchedEffect(animatedScroll) {
        if (animatedScroll >= 0.999f) {
            delay(800)
            visible = false
            delay(500)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    visible = false
                }
        ) {
            CreditRoll(lines = lines, progress = animatedScroll)

            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close credits",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(20.dp)
                    .clickable { visible = false }
            )
        }
    }
}

@Composable
private fun CreditRoll(lines: List<CreditLine>, progress: Float) {
    val estimatedTotalHeightPx = remember(lines) {
        lines.sumOf { line ->
            when (line) {
                is CreditLine.Heading -> 90
                is CreditLine.Role -> 40
                is CreditLine.Name -> 60
                is CreditLine.Space -> line.heightDp
            }
        } * 3.5f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .graphicsLayer {
                    translationY = (1f - progress) * (estimatedTotalHeightPx * 0.55f) -
                        (progress * estimatedTotalHeightPx * 0.55f)
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            lines.forEach { line ->
                when (line) {
                    is CreditLine.Heading -> Text(
                        text = line.text,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    is CreditLine.Role -> Text(
                        text = line.text,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 24.dp, bottom = 2.dp)
                    )
                    is CreditLine.Name -> Text(
                        text = line.text,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    is CreditLine.Space -> Spacer(Modifier.height(line.heightDp.dp))
                }
            }
        }
    }
}

@Composable
fun HoldToRevealTrigger(
    onRevealed: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Int = 1600,
    content: @Composable () -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }
    val progress = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isHolding) {
        if (isHolding) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdDurationMs, easing = LinearEasing))
            if (progress.value >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRevealed()
            }
        } else {
            progress.stop()
            progress.animateTo(0f, tween(200))
        }
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = { offset ->
                    pressPosition = offset
                    isHolding = true
                    tryAwaitRelease()
                    isHolding = false
                }
            )
        }
    ) {
        content()

        if (isHolding) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ringRadius = 26f
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = ringRadius,
                    center = pressPosition
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.5f),
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = Offset(
                        pressPosition.x - ringRadius,
                        pressPosition.y - ringRadius
                    ),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

val bitCreditLines = listOf(
    CreditLine.Space(120),
    CreditLine.Heading("BIT"),
    CreditLine.Name("Offline On-Device AI Assistant"),
    CreditLine.Space(64),

    CreditLine.Role("CREATED BY"),
    CreditLine.Name("Nekkanti Jaswanth Sanjay"),
    CreditLine.Space(48),

    CreditLine.Role("SYSTEM ARCHITECTURE"),
    CreditLine.Name("Jaswanth Sanjay"),

    CreditLine.Role("INTERFACE DESIGN"),
    CreditLine.Name("Jaswanth Sanjay"),

    CreditLine.Role("ON-DEVICE INFERENCE"),
    CreditLine.Name("llama.cpp"),
    CreditLine.Space(64),

    CreditLine.Role("BUILT WITH"),
    CreditLine.Name("Kotlin · Jetpack Compose"),
    CreditLine.Name("Material Design 3"),
    CreditLine.Space(64),

    CreditLine.Role("SPECIAL THANKS"),
    CreditLine.Name("llama.cpp developers"),
    CreditLine.Name("Everyone who believed offline-first AI was worth building"),
    CreditLine.Space(96),

    CreditLine.Heading("Thank you for using BIT"),
    CreditLine.Space(160)
)
