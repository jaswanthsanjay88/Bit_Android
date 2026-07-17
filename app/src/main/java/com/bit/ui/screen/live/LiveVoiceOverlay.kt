package com.bit.ui.screen.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.service.AudioCaptureService
import com.bit.stt.SherpaSTTEngine
import com.bit.tts.TTSManager
import com.bit.tts.TTSSettings
import com.bit.ui.components.TopBlurScrim
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── State Machine ─────────────────────────────────────────────────────────────

sealed class LiveModeState {
    object Idle : LiveModeState()
    object Listening : LiveModeState()
    data class Thinking(val partial: String = "") : LiveModeState()
    data class Speaking(val text: String) : LiveModeState()
    data class Error(val message: String) : LiveModeState()
}

// ── Live Mode Controller ─────────────────────────────────────────────────────

class LiveModeController(
    private val context: Context,
    private val chatViewModel: ChatViewModel
) {
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + Job())
    private val audioCaptureService = AudioCaptureService(context)

    private val _state = MutableStateFlow<LiveModeState>(LiveModeState.Idle)
    val state: StateFlow<LiveModeState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var captureJob: Job? = null
    private var llmObserveJob: Job? = null
    private val pcmStream = ByteArrayOutputStream()

    private var silenceStartTime = 0L
    private var hasSpokenInSession = false

    fun start() {
        if (!audioCaptureService.hasRecordPermission()) {
            _state.value = LiveModeState.Error("Microphone permission required")
            return
        }
        if (!SherpaSTTEngine.hasModelFiles(context)) {
            _state.value = LiveModeState.Error("STT model files not ready. Please download STT in Settings.")
            return
        }

        _state.value = LiveModeState.Listening
        startListeningLoop()
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        llmObserveJob?.cancel()
        llmObserveJob = null
        audioCaptureService.stopCapture()
        TTSManager.stopPlayback()
        _state.value = LiveModeState.Idle
    }

    fun interrupt() {
        TTSManager.stopPlayback()
        chatViewModel.stop()
        llmObserveJob?.cancel()
        llmObserveJob = null
        _state.value = LiveModeState.Listening
        startListeningLoop()
    }

    fun submitPrompt(prompt: String) {
        if (prompt.isBlank()) return
        TTSManager.stopPlayback()
        captureJob?.cancel()
        audioCaptureService.stopCapture()

        _state.value = LiveModeState.Thinking(prompt)
        chatViewModel.sendTextMessage(prompt)

        observeLlmReply()
    }

    private fun startListeningLoop() {
        captureJob?.cancel()
        pcmStream.reset()
        silenceStartTime = 0L
        hasSpokenInSession = false

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                audioCaptureService.startCapture().collect { chunk ->
                    val rms = audioCaptureService.calculateRMS(chunk)
                    _audioLevel.value = rms

                    // State-based Mic handling
                    when (val currentState = _state.value) {
                        is LiveModeState.Listening -> {
                            pcmStream.write(chunk)
                            if (rms > 0.03f) {
                                hasSpokenInSession = true
                                silenceStartTime = 0L
                            } else if (hasSpokenInSession) {
                                if (silenceStartTime == 0L) {
                                    silenceStartTime = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - silenceStartTime > 650) {
                                    // 650ms silence detected after user speech -> finish speech
                                    onUserSpeechFinished()
                                }
                            }
                        }
                        is LiveModeState.Speaking -> {
                            // Voice-triggered Barge-In detection
                            if (rms > 0.06f) {
                                withContext(Dispatchers.Main) {
                                    interrupt()
                                }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = LiveModeState.Error(e.message ?: "Audio capture error")
                }
            }
        }
    }

    private fun onUserSpeechFinished() = scope.launch(Dispatchers.IO) {
        audioCaptureService.stopCapture()
        val audioBytes = pcmStream.toByteArray()
        pcmStream.reset()

        if (audioBytes.isEmpty()) {
            withContext(Dispatchers.Main) { _state.value = LiveModeState.Idle }
            return@launch
        }

        withContext(Dispatchers.Main) {
            _state.value = LiveModeState.Thinking("Processing speech…")
        }

        val text = SherpaSTTEngine.transcribe(context, audioBytes).trim()
        if (text.isBlank()) {
            withContext(Dispatchers.Main) {
                _state.value = LiveModeState.Idle
                startListeningLoop()
            }
            return@launch
        }

        withContext(Dispatchers.Main) {
            submitPrompt(text)
        }
    }

    private fun observeLlmReply() {
        llmObserveJob?.cancel()
        llmObserveJob = scope.launch {
            var speakStarted = false

            snapshotFlow {
                Pair(chatViewModel.messages.lastOrNull(), chatViewModel.isGenerating.value)
            }.collect { (lastMsg, isGenerating) ->
                if (lastMsg != null && lastMsg.role == Role.Assistant) {
                    val fullText = lastMsg.content.content
                    // Clean thinking tags if present
                    val cleanText = fullText.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()

                    if (cleanText.isNotBlank()) {
                        if (!speakStarted) {
                            speakStarted = true
                            _state.value = LiveModeState.Speaking(cleanText)
                        } else {
                            _state.value = LiveModeState.Speaking(cleanText)
                        }

                        // Feed to TTS if TTS is ready
                        if (TTSManager.isModelLoaded.value && !TTSManager.isPlaying.value && isGenerating) {
                            val settings = TTSSettings(
                                speed = 1.0f,
                                useNNAPI = false,
                                voice = "0"
                            )
                            TTSManager.speak(cleanText, settings, lastMsg.msgId)
                        }
                    }
                }

                // If LLM finished generation
                if (!isGenerating && _state.value is LiveModeState.Speaking) {
                    delay(800)
                    if (!TTSManager.isPlaying.value) {
                        _state.value = LiveModeState.Idle
                    }
                }
            }
        }
    }

    fun release() {
        stop()
    }
}

// ── Live Voice UI Overlay Composable ─────────────────────────────────────────

@Composable
fun LiveVoiceOverlay(
    chatViewModel: ChatViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { LiveModeController(context, chatViewModel) }
    val state by controller.state.collectAsState()
    val audioLevel by controller.audioLevel.collectAsState()

    DisposableEffect(Unit) {
        controller.start()
        onDispose {
            controller.release()
        }
    }

    val suggestions = remember {
        listOf(
            "Italian pasta dishes",
            "Grilled chicken and vegetables",
            "Stir fry with rice",
            "Tacos or burrito bowls",
            "Simple salad with protein"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (state is LiveModeState.Speaking) {
                    controller.interrupt()
                }
            }
    ) {
        // Top Blur Scrim
        TopBlurScrim(height = 90.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Status title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(40.dp))
                Surface(
                    color = Color(0x22FFFFFF),
                    shape = RoundedCornerShape(100.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
                ) {
                    Text(
                        text = "LIVE MODE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(TnIcons.X, contentDescription = "Close", tint = Color.White)
                }
            }

            // Center: Live Animated Orb & Spoken Captions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                LiveOrb(state = state, audioLevel = audioLevel)

                Spacer(modifier = Modifier.height(36.dp))

                // Caption text
                AnimatedContent(
                    targetState = state,
                    label = "live_caption"
                ) { currentState ->
                    val caption = when (currentState) {
                        is LiveModeState.Idle -> "How can I help you this late night?"
                        is LiveModeState.Listening -> "Listening…"
                        is LiveModeState.Thinking -> "Thinking…"
                        is LiveModeState.Speaking -> currentState.text
                        is LiveModeState.Error -> currentState.message
                    }
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(min = 60.dp)
                    )
                }

                // Suggestions list when Idle
                AnimatedVisibility(
                    visible = state is LiveModeState.Idle,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .fillMaxWidth(0.85f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        suggestions.forEach { suggestion ->
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { controller.submitPrompt(suggestion) }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Bottom Floating Control Bar Pill (+ / Stop / X)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                if (state is LiveModeState.Speaking || state is LiveModeState.Listening) {
                    Text(
                        text = "Tap anywhere to interrupt",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Surface(
                    color = Color(0xFF1F1F1F),
                    shape = RoundedCornerShape(100.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Plus (+) button
                        IconButton(
                            onClick = { /* Add prompt attachment */ },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0x22FFFFFF), CircleShape)
                        ) {
                            Icon(TnIcons.Plus, contentDescription = "Add", tint = Color.White)
                        }

                        // Center Stop / Mic button
                        IconButton(
                            onClick = {
                                if (state is LiveModeState.Speaking || state is LiveModeState.Listening) {
                                    controller.interrupt()
                                } else {
                                    controller.start()
                                }
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (state is LiveModeState.Speaking || state is LiveModeState.Listening) TnIcons.PlayerStop else TnIcons.Sparkles,
                                contentDescription = "Stop or Start",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Close (X) button
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0x22FFFFFF), CircleShape)
                        ) {
                            Icon(TnIcons.X, contentDescription = "Close Live Mode", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ── Live Orb Composable ──────────────────────────────────────────────────────

@Composable
fun LiveOrb(
    state: LiveModeState,
    audioLevel: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orb_rotation")

    val scale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = when (state) {
            is LiveModeState.Listening -> 1.12f + (audioLevel * 0.4f)
            is LiveModeState.Speaking -> 1.08f + (audioLevel * 0.3f)
            else -> 1.03f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state is LiveModeState.Listening) 5000 else 18000, easing = LinearEasing)
        ),
        label = "orb_rotation_val"
    )

    val strokeColor = Color.White

    Canvas(
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            }
    ) {
        val spokes = 12
        val centerPoint = this.center
        val maxLen = size.minDimension / 2.2f

        for (i in 0 until spokes) {
            val angle = (i * 360f / spokes) * (PI / 180f)
            val factor = if (i % 2 == 0) 1.0f else 0.65f
            val len = maxLen * factor * (1f + audioLevel * 0.3f)

            val end = Offset(
                centerPoint.x + cos(angle).toFloat() * len,
                centerPoint.y + sin(angle).toFloat() * len
            )

            drawLine(
                color = strokeColor.copy(alpha = if (i % 2 == 0) 0.9f else 0.5f),
                start = centerPoint,
                end = end,
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}
