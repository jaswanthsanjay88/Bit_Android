package com.bit.ui.screen.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.bit.models.messages.ContentType
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.service.AudioCaptureService
import com.bit.stt.SherpaSTTEngine
import com.bit.tts.TTSManager
import com.bit.tts.TTSSettings
import com.bit.ui.components.TopBlurScrim
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Motion
import com.bit.ui.theme.MotionDuration
import com.bit.ui.theme.MotionEasing
import com.bit.ui.theme.bouncyClick
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
    private var previousMessages = setOf<Messages>()
    private var bargeInConsecutiveFrames = 0
    private var lastBargeInTimestamp = 0L

    fun start() {
        if (!audioCaptureService.hasRecordPermission()) {
            _state.value = LiveModeState.Error("Microphone permission required")
            return
        }
        if (!SherpaSTTEngine.hasModelFiles(context)) {
            _state.value = LiveModeState.Error("STT model files not ready. Please download STT in Settings.")
            return
        }

        previousMessages = chatViewModel.messages.toSet()
        chatViewModel.isLiveVoiceModeActive.value = true
        _state.value = LiveModeState.Listening
        scope.launch { SherpaSTTEngine.enterLiveSession(context) }
        startListeningLoop()
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        llmObserveJob?.cancel()
        llmObserveJob = null
        audioCaptureService.stopCapture()
        TTSManager.stopPlayback()
        SherpaSTTEngine.exitLiveSession()
        chatViewModel.isLiveVoiceModeActive.value = false
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

    private fun bargeInInterrupt() {
        TTSManager.stopPlayback()
        chatViewModel.stop()
        llmObserveJob?.cancel()
        llmObserveJob = null
        hasSpokenInSession = true
        silenceStartTime = 0L
        _state.value = LiveModeState.Listening
    }

    fun stopAndTranscribe() {
        if (_state.value is LiveModeState.Listening) {
            _state.value = LiveModeState.Thinking("Processing speech…")
            onUserSpeechFinished()
        }
    }

    fun submitPrompt(prompt: String) {
        if (prompt.isBlank()) return
        TTSManager.stopPlayback()
        captureJob?.cancel()
        audioCaptureService.stopCapture()

        previousMessages = chatViewModel.messages.toSet()
        _state.value = LiveModeState.Thinking(prompt)
        chatViewModel.sendTextMessage(prompt)

        observeLlmReply()
    }

    private fun startListeningLoop() {
        captureJob?.cancel()
        pcmStream.reset()
        silenceStartTime = 0L
        hasSpokenInSession = false
        bargeInConsecutiveFrames = 0

        captureJob = scope.launch(Dispatchers.IO) {
            try {
                audioCaptureService.startCapture().collect { chunk ->
                    val rms = audioCaptureService.calculateRMS(chunk)
                    _audioLevel.value = rms

                    // State-based Mic handling
                    when (val currentState = _state.value) {
                        is LiveModeState.Listening -> {
                            pcmStream.write(chunk)
                            if (rms > 0.015f) {
                                hasSpokenInSession = true
                                silenceStartTime = 0L
                            } else if (hasSpokenInSession) {
                                if (silenceStartTime == 0L) {
                                    silenceStartTime = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - silenceStartTime > 950) {
                                    // 950ms silence detected after user speech -> finish speech
                                    _state.value = LiveModeState.Thinking("Processing speech…")
                                    onUserSpeechFinished()
                                }
                            }
                        }
                        is LiveModeState.Speaking -> {
                            // Continuous VAD with hysteresis during Speaking state
                            val now = System.currentTimeMillis()
                            if (now - lastBargeInTimestamp > 800) { // Cooldown after previous barge-in
                                if (rms > 0.04f) { // Energy threshold for sustained speech
                                    bargeInConsecutiveFrames++
                                    pcmStream.write(chunk) // Retain speech start audio
                                    if (bargeInConsecutiveFrames >= 3) { // 3 consecutive ~100ms frames
                                        bargeInConsecutiveFrames = 0
                                        lastBargeInTimestamp = now
                                        withContext(Dispatchers.Main) {
                                            bargeInInterrupt()
                                        }
                                    }
                                } else {
                                    bargeInConsecutiveFrames = 0
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

        // Minimum audio duration check: 16kHz 16-bit mono = 32000 bytes/sec -> 0.45s = 14400 bytes
        if (audioBytes.size < 14400) {
            withContext(Dispatchers.Main) {
                _state.value = LiveModeState.Idle
                startListeningLoop()
            }
            return@launch
        }

        // Check overall audio RMS energy to filter out ambient room hum/silence
        val overallRms = audioCaptureService.calculateRMS(audioBytes)
        if (overallRms < 0.012f) {
            withContext(Dispatchers.Main) {
                _state.value = LiveModeState.Idle
                startListeningLoop()
            }
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

    private fun cleanTextForLiveVoice(text: String): String {
        var cleaned = text
        
        // Remove completed blocks
        cleaned = cleaned.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("<tool_response>.*?</tool_response>", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("<tool_results>.*?</tool_results>", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("\\{\\s*\"tool_calls\"\\s*:[^\\]]*\\]\\s*\\}", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("\\{\\s*\"tool_calls\"\\s*:[^}]*\\}\\s*", RegexOption.DOT_MATCHES_ALL), "")
        cleaned = cleaned.replace(Regex("<[^>]+>.*?</[^>]+>", RegexOption.DOT_MATCHES_ALL), "")

        // Remove markdown code blocks and inline code
        cleaned = cleaned.replace(Regex("```[\\s\\S]*?```"), "")
        cleaned = cleaned.replace(Regex("`[^`]*`"), "")

        // Truncate at any unclosed tags/JSON block/code block
        val indices = listOf(
            cleaned.indexOf("<think"),
            cleaned.indexOf("<tool_call"),
            cleaned.indexOf("<tool_response"),
            cleaned.indexOf("<tool_results"),
            cleaned.indexOf("<call"),
            cleaned.indexOf("{\"tool_calls"),
            cleaned.indexOf("```"),
            cleaned.indexOf("`")
        ).filter { it != -1 }
        
        if (indices.isNotEmpty()) {
            cleaned = cleaned.substring(0, indices.minOrNull()!!)
        }

        // Clean up markdown formatting symbols for clean visual display and spoken voice
        cleaned = cleaned.replace(Regex("[*_#~>]"), "") // remove bold, italics, headers, blockquotes
        cleaned = cleaned.replace(Regex("\\[([^]]+)]\\([^)]+\\)")) { it.groupValues[1] } // extract link labels
        
        return cleaned.trim()
    }

    private fun observeLlmReply() {
        llmObserveJob?.cancel()
        llmObserveJob = scope.launch {
            var speakStarted = false
 
            snapshotFlow {
                Pair(chatViewModel.messages.lastOrNull(), chatViewModel.isGenerating.value)
            }.collect { (lastMsg, isGenerating) ->
                val isAssistantText = lastMsg != null && lastMsg !in previousMessages && lastMsg.role == Role.Assistant &&
                        (lastMsg.content.contentType == ContentType.Text || lastMsg.content.contentType == ContentType.TextWithImage)

                if (isAssistantText) {
                    val fullText = lastMsg.content.content
                    val cleanText = cleanTextForLiveVoice(fullText)
 
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
                    } else if (isGenerating) {
                        _state.value = LiveModeState.Thinking("Thinking…")
                    }
                } else if (isGenerating) {
                    _state.value = LiveModeState.Thinking("Thinking…")
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

    val infiniteTransition = rememberInfiniteTransition(label = "glow_visual")

    // Thinking state breathing loop
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking_breathing_scale"
    )
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking_breathing_alpha"
    )

    // Idle state breathing loop
    val idleBreathingScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breathing_scale"
    )
    val idleBreathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breathing_alpha"
    )

    val playbackLevel by TTSManager.playbackAmplitude.collectAsState()

    val effectiveAmp = when (state) {
        is LiveModeState.Listening -> audioLevel.coerceIn(0f, 1f)
        is LiveModeState.Speaking -> playbackLevel.coerceIn(0f, 1f)
        else -> 0f
    }

    val targetGlowScale = when (state) {
        is LiveModeState.Thinking -> breathingScale
        is LiveModeState.Listening, is LiveModeState.Speaking -> 0.9f + (effectiveAmp * 0.4f)
        else -> idleBreathingScale
    }

    val targetGlowAlpha = when (state) {
        is LiveModeState.Thinking -> breathingAlpha
        is LiveModeState.Listening, is LiveModeState.Speaking -> (0.4f + (effectiveAmp * 0.4f)).coerceIn(0.4f, 0.8f)
        else -> idleBreathingAlpha
    }

    val glowScale by animateFloatAsState(
        targetValue = targetGlowScale,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "glow_scale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = targetGlowAlpha,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "glow_alpha"
    )

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
        // Bottom reactive glow (behind the control bar)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    scaleY = glowScale
                    alpha = glowAlpha
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.0f)
                }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.35f)
                        )
                    )
                )
        )
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
            // Header: Clean close button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(onClick = onClose) {
                    Icon(TnIcons.X, contentDescription = "Close", tint = Color.White)
                }
            }

            // Center: Spoken Captions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                // Caption text
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        (fadeIn(tween(MotionDuration.enter, easing = MotionEasing.standard)) + scaleIn(initialScale = 0.92f, animationSpec = Motion.interactive())) togetherWith
                        (fadeOut(tween(MotionDuration.exit, easing = MotionEasing.standard)) + scaleOut(targetScale = 0.92f, animationSpec = Motion.exit()))
                    },
                    label = "live_caption"
                ) { currentState ->
                    val caption = when (currentState) {
                        is LiveModeState.Idle -> "How can I help you?"
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
            }

            // Bottom Floating Control Bar Pill (+ / Stop / X)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                val statusText = when (state) {
                    is LiveModeState.Speaking -> "Tap anywhere to interrupt"
                    is LiveModeState.Listening -> "Listening..."
                    is LiveModeState.Thinking -> "Thinking..."
                    else -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Surface(
                    color = Color(0xCC1F1F1F),
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
                                .background(Color(0xFF2A2A2A), CircleShape)
                        ) {
                            Icon(TnIcons.Plus, contentDescription = "Add", tint = Color.White)
                        }

                        // Center Stop / Mic button
                        IconButton(
                            onClick = {
                                if (state is LiveModeState.Speaking || state is LiveModeState.Thinking) {
                                    controller.interrupt()
                                } else if (state is LiveModeState.Listening) {
                                    controller.stopAndTranscribe()
                                } else if (state is LiveModeState.Idle) {
                                    controller.start()
                                }
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White, CircleShape)
                        ) {
                            val isActionStop = state is LiveModeState.Speaking || state is LiveModeState.Thinking
                            AnimatedContent(
                                targetState = isActionStop,
                                transitionSpec = {
                                    (scaleIn(initialScale = 0.7f, animationSpec = Motion.interactive()) + fadeIn(tween(MotionDuration.stateChange))) togetherWith
                                    (scaleOut(targetScale = 0.7f, animationSpec = Motion.exit()) + fadeOut(tween(MotionDuration.exit)))
                                },
                                label = "live_center_btn_icon"
                            ) { stopIcon ->
                                Icon(
                                    imageVector = if (stopIcon) TnIcons.PlayerStop else TnIcons.LiveWaveform,
                                    contentDescription = "Stop or Start",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Close (X) button
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF2A2A2A), CircleShape)
                        ) {
                            Icon(TnIcons.X, contentDescription = "Close Live Mode", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}


