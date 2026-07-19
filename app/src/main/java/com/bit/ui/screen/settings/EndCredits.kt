package com.bit.ui.screen.settings

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ensures fair non-repeating song selection (deck shuffle algorithm).
 * Every song in [audioResIds] plays once before any song repeats (e.g. 1 -> 4 -> 3 -> 2),
 * and the first song of the next deck is guaranteed not to match the last played song (preventing 2 -> 2 back-to-back repeats).
 */
object CreditsAudioDeck {
    private const val PREFS_NAME = "bit_credits_audio_prefs"
    private const val KEY_LAST_AUDIO_ID = "last_audio_res_id"
    private const val KEY_REMAINING_DECK = "remaining_deck_res_ids"

    fun getNextAudioResId(context: Context, audioResIds: List<Int>): Int {
        if (audioResIds.isEmpty()) return 0
        if (audioResIds.size == 1) return audioResIds[0]

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastId = prefs.getInt(KEY_LAST_AUDIO_ID, -1)
        val savedDeckStr = prefs.getString(KEY_REMAINING_DECK, null)

        var deck = savedDeckStr?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in audioResIds } ?: emptyList()

        if (deck.isEmpty()) {
            var newDeck = audioResIds.shuffled()
            if (newDeck.first() == lastId && newDeck.size > 1) {
                newDeck = newDeck.drop(1) + newDeck.first()
            }
            deck = newDeck
        }

        val nextId = deck.first()
        val remainingDeck = deck.drop(1)

        prefs.edit()
            .putInt(KEY_LAST_AUDIO_ID, nextId)
            .putString(KEY_REMAINING_DECK, remainingDeck.joinToString(","))
            .apply()

        return nextId
    }
}

sealed class CreditLine {
    data class Heading(val text: String) : CreditLine()
    data class Role(val text: String) : CreditLine()
    data class Name(val text: String) : CreditLine()
    data class AccentText(val text: String) : CreditLine()
    data class Subtext(val text: String) : CreditLine()
    data class Space(val heightDp: Int = 48) : CreditLine()
}


@Composable
fun EndCreditsOverlay(
    audioResIds: List<Int>,
    lines: List<CreditLine>,
    onDismiss: () -> Unit,
    scrollSpeedDpPerSec: Float = 28f
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenHeightPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }

    val estimatedTotalHeightPx = remember(lines) {
        lines.sumOf { line ->
            when (line) {
                is CreditLine.Heading -> 90
                is CreditLine.Role -> 50
                is CreditLine.Name -> 40
                is CreditLine.AccentText -> 45
                is CreditLine.Subtext -> 35
                is CreditLine.Space -> line.heightDp
            }
        } * with(density) { 3.2.dp.toPx() }
    }

    val totalTravelPx = screenHeightPx + estimatedTotalHeightPx
    val scrollSpeedPxPerMs = with(density) { scrollSpeedDpPerSec.dp.toPx() } / 1000f
    val scrollDurationMs = remember(totalTravelPx, scrollSpeedPxPerMs) {
        (totalTravelPx / scrollSpeedPxPerMs).toLong().coerceAtLeast(1000L)
    }

    var visible by remember { mutableStateOf(true) }
    var scrollProgress by remember { mutableFloatStateOf(0f) }
    var accumulatedAudioMs by remember { mutableLongStateOf(0L) }

    // Guard to prevent finger release of the trigger long-press from immediately dismissing credits
    var dismissEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1000) // Ignore any dismiss click for the first 1s of credits presentation
        dismissEnabled = true
    }

    // Intercept system back button to cleanly close overlay
    androidx.activity.compose.BackHandler(enabled = visible) {
        visible = false
    }

    val selectedAudioResId = remember {
        CreditsAudioDeck.getNextAudioResId(context, audioResIds)
    }

    // Track the active MediaPlayer so completion-spawned players are also released on dismiss
    val activePlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val scope = rememberCoroutineScope()

    // --- audio playback lifecycle ---
    DisposableEffect(Unit) {
        val player = MediaPlayer.create(context, selectedAudioResId)?.apply {
            isLooping = false
            setVolume(0f, 0f)
        }
        activePlayer.value = player

        player?.setOnCompletionListener { finished ->
            try {
                val dur = finished.duration.toLong()
                finished.release()
                accumulatedAudioMs += dur
                val nextId = CreditsAudioDeck.getNextAudioResId(context, audioResIds)
                val next = MediaPlayer.create(context, nextId)?.apply {
                    setVolume(0f, 0f)
                    setOnCompletionListener { it2 ->
                        try {
                            val d2 = it2.duration.toLong()
                            it2.release()
                            accumulatedAudioMs += d2
                        } catch (_: Exception) {}
                    }
                }
                activePlayer.value = next
                next?.start()
                // Fade in chained track smoothly over ~300ms
                CoroutineScope(Dispatchers.Main).launch {
                    for (i in 0..5) {
                        next?.setVolume(i / 5f, i / 5f)
                        delay(60)
                    }
                }
            } catch (_: Exception) {
                // Ignore audio transitions on exit
            }
        }

        onDispose {
            val p = activePlayer.value
            try {
                p?.stop()
            } catch (_: Exception) { }
            try {
                p?.release()
            } catch (_: Exception) { }
            activePlayer.value = null
        }
    }

    // Synchronized orchestration: derive scroll progress directly from MediaPlayer's position every VSYNC frame
    LaunchedEffect(Unit) {
        val player = activePlayer.value
        player?.start()

        // Volume fade-in runs concurrently in parallel so scroll begins at t=0 immediately
        launch {
            for (i in 0..10) {
                player?.setVolume(i / 10f, i / 10f)
                delay(60)
            }
        }

        while (isActive && visible) {
            withFrameNanos {
                val p = activePlayer.value
                val currentPos = try {
                    if (p != null && p.isPlaying) p.currentPosition.toLong() else 0L
                } catch (_: Exception) {
                    0L
                }
                val totalElapsedMs = accumulatedAudioMs + currentPos
                val currentProgress = (totalElapsedMs.toFloat() / scrollDurationMs.toFloat()).coerceIn(0f, 1f)

                scrollProgress = currentProgress

                if (currentProgress >= 1f) {
                    visible = false
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) {
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
                    if (dismissEnabled) {
                        visible = false
                    }
                }
        ) {
            CreditRoll(lines = lines, progress = scrollProgress)

            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close credits",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(20.dp)
                    .clickable {
                        if (dismissEnabled) {
                            visible = false
                        }
                    }
            )
        }
    }
}

@Composable
private fun CreditRoll(lines: List<CreditLine>, progress: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        
        val estimatedTotalHeightPx = remember(lines) {
            lines.sumOf { line ->
                when (line) {
                    is CreditLine.Heading -> 90
                    is CreditLine.Role -> 50
                    is CreditLine.Name -> 40
                    is CreditLine.AccentText -> 45
                    is CreditLine.Subtext -> 35
                    is CreditLine.Space -> line.heightDp
                }
            } * with(density) { 3.2.dp.toPx() }
        }

        // Top of column starts exactly at the bottom of the screen (screenHeightPx/2 offset from center)
        val startY = (screenHeightPx + estimatedTotalHeightPx) / 2f
        // Bottom of column ends exactly at the top of the screen (-screenHeightPx/2 offset from center)
        val endY = -(screenHeightPx + estimatedTotalHeightPx) / 2f
        val currentY = startY + progress * (endY - startY)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .graphicsLayer {
                    translationY = currentY
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 28.dp, bottom = 6.dp)
                    )
                    is CreditLine.Name -> Text(
                        text = line.text,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                    is CreditLine.AccentText -> Text(
                        text = line.text,
                        color = Color(0xFF9FA8DA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 32.dp, bottom = 4.dp)
                    )
                    is CreditLine.Subtext -> Text(
                        text = line.text,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
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

    var hasTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            hasTriggered = false
            progress.snapTo(0f)
            progress.animateTo(1f, tween(holdDurationMs, easing = LinearEasing))
            if (progress.value >= 1f) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                hasTriggered = true
            }
        } else {
            progress.stop()
            if (hasTriggered) {
                onRevealed()
                hasTriggered = false
            }
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
    CreditLine.Name("Offline On-Device AI Client"),
    CreditLine.Space(48),

    CreditLine.Role("CREATOR & ARCHITECT"),
    CreditLine.Name("Jaswanth Sanjay Nekkanti"),
    CreditLine.Space(48),

    // ENGINES AND RUNTIMES
    CreditLine.Role("ENGINES AND RUNTIMES"),
    CreditLine.Name("llama.kt SDK"),
    CreditLine.Name("ggml and KleidiAI runtime"),
    CreditLine.Name("sherpa-onnx by k2-fsa team"),
    CreditLine.Name("Stable Diffusion engine"),
    CreditLine.Name("ONNX Runtime"),
    CreditLine.Name("PDFium and miniz"),
    CreditLine.Space(48),

    // MODELS IN THE CATALOG
    CreditLine.Role("MODELS IN THE CATALOG"),
    CreditLine.Name("Llama by Meta AI"),
    CreditLine.Name("Qwen3 by the Qwen team"),
    CreditLine.Name("Mistral and Gemma"),
    CreditLine.Name("Whisper by OpenAI"),
    CreditLine.Name("Piper voices by Michael Hansen"),
    CreditLine.Name("Hosted on HuggingFace"),
    CreditLine.Space(48),

    // DOCUMENT AND RAG PIPELINE
    CreditLine.Role("DOCUMENT AND RAG PIPELINE"),
    CreditLine.Name("PDFBox Android by Tom Roush"),
    CreditLine.Name("Jsoup by Jonathan Hedley"),
    CreditLine.Name("Apache Commons Compress"),
    CreditLine.Space(48),

    // ANDROID STACK
    CreditLine.Role("ANDROID STACK"),
    CreditLine.Name("Android OS and AOSP"),
    CreditLine.Name("Jetpack Compose and Material Design 3"),
    CreditLine.Name("Hilt and Room Database"),
    CreditLine.Name("Kotlin and Coroutines"),
    CreditLine.Name("kotlinx.serialization"),
    CreditLine.Space(48),

    // BUILT WITH
    CreditLine.Role("BUILT WITH"),
    CreditLine.Name("Android Studio"),
    CreditLine.Name("Gradle"),
    CreditLine.Space(48),

    // SPECIAL THANKS
    CreditLine.Role("SPECIAL THANKS"),
    CreditLine.Name("Every person who installed this"),
    CreditLine.Name("Every bug report"),
    CreditLine.Name("The open-source community"),
    CreditLine.Space(64),

    // FOOTER
    CreditLine.AccentText("Made with care by Jaswanth Sanjay Nekkanti"),
    CreditLine.Subtext("Tap anywhere to leave."),
    CreditLine.Space(160)
)
