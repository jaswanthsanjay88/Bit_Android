package com.bit.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.bit.global.Standards
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionToggleGroup
import com.bit.ui.components.CaptionText
import com.bit.ui.components.SettingsSwitchRow
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.tts.TTSSettings
import com.bit.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

// ── Constants ──

internal val SUPPORTED_LANGUAGES = listOf("en" to "EN")
internal val DEFAULT_VOICES = (0..9).map { it.toString() }

data class TtsModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val size: String,
    val url: String
)

internal val AVAILABLE_TTS_MODELS = listOf(
    TtsModelInfo(
        id = "vits-ljs-tts",
        name = "VITS LJSpeech (Default)",
        description = "Single-speaker English voice, light and fast",
        size = "~40 MB",
        url = "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/vits-ljs.onnx"
    ),
    TtsModelInfo(
        id = "vits-piper-en_us-amy-low",
        name = "Piper US Amy (English)",
        description = "High-quality low-latency English female voice",
        size = "~28 MB",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2"
    ),
    TtsModelInfo(
        id = "kokoro-multi-lang-v1_0",
        name = "Kokoro v1.0 (Multi-Language)",
        description = "Kokoro 82M multilingual text-to-speech engine",
        size = "~84 MB",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
    )
)

// ── TTS Settings Section ──

internal fun LazyListScope.ttsSettingsSection(
    installedTtsModelId: String?,
    ttsDownloadStates: Map<String, ModelDownloadService.DownloadState>,
    ttsModelLoaded: Boolean,
    loadTTSOnStart: Boolean,
    ttsSettings: TTSSettings,
    voices: List<String>,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "Text-to-Speech",
            icon = TnIcons.Adjustments,
            description = "Configure offline voice synthesis and acoustics"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                // Download cards for available TTS models
                Text(
                    text = "Available Models",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Glass.TextPrimary,
                    modifier = Modifier.padding(top = Standards.SpacingXs)
                )

                AVAILABLE_TTS_MODELS.forEach { ttsModel ->
                    val isInstalled = installedTtsModelId == ttsModel.id
                    val downloadState = ttsDownloadStates[ttsModel.id]
                    
                    ModelDownloadCard(
                        title = ttsModel.name,
                        description = "${ttsModel.description} · ${ttsModel.size}",
                        downloadState = downloadState,
                        onDownload = { viewModel.downloadTtsModel(ttsModel.id, ttsModel.name, ttsModel.url) },
                        successText = if (isInstalled && ttsModelLoaded) "Active — Ready" else if (isInstalled) "Installed — Ready" else "Downloaded",
                        isInstalled = isInstalled
                    )
                }

                GlassDivider()

                SettingsSwitchRow(
                    title = "Load TTS on App Start",
                    description = "Auto-load TTS model when app launches",
                    checked = loadTTSOnStart,
                    onCheckedChange = { viewModel.setLoadTTSOnStart(it) }
                )

                GlassDivider()

                // Voice Picker (Speaker ID Selection)
                if (voices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                        Text(
                            text = "Speaker ID",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Glass.TextPrimary
                        )
                        if (voices.size <= 5) {
                            ActionToggleGroup(
                                items = voices,
                                selectedItem = ttsSettings.voice,
                                onItemSelected = { viewModel.updateVoice(it) },
                                itemLabel = { "Speaker $it" },
                                enabled = ttsModelLoaded
                            )
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(voices) { voice ->
                                    val isSelected = voice == ttsSettings.voice
                                    val itemBg = if (isSelected) MaterialTheme.colorScheme.primary else Glass.Surface
                                    val itemBorderColor = if (isSelected) MaterialTheme.colorScheme.primary else Glass.BorderSubtle
                                    val itemTextColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Glass.TextPrimary

                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(Standards.RadiusSm))
                                            .then(
                                                if (ttsModelLoaded) Modifier.clickable { viewModel.updateVoice(voice) }
                                                else Modifier
                                            ),
                                        color = itemBg,
                                        border = BorderStroke(1.dp, itemBorderColor),
                                        shape = RoundedCornerShape(Standards.RadiusSm)
                                    ) {
                                        Text(
                                            text = "Speaker $voice",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = itemTextColor,
                                            modifier = Modifier.padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    GlassDivider()
                }

                // Speed Slider
                Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Playback Speed",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Glass.TextPrimary
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(Standards.SpacingXs)
                        ) {
                            Text(
                                text = "${"%.2f".format(ttsSettings.speed)}x",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = Standards.SpacingSm, vertical = Standards.SpacingXxs)
                            )
                        }
                    }

                    Slider(
                        value = ttsSettings.speed,
                        onValueChange = { viewModel.updateSpeed((it * 20).roundToInt() / 20f) },
                        valueRange = 0.5f..2.0f,
                        enabled = ttsModelLoaded,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Glass.Border
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        CaptionText(text = "0.5x")
                        CaptionText(text = "1.0x")
                        CaptionText(text = "2.0x")
                    }
                }

                GlassDivider()

                SettingsSwitchRow(
                    title = "Auto-speak",
                    description = "Automatically speak assistant responses",
                    checked = ttsSettings.autoSpeak,
                    onCheckedChange = { viewModel.updateAutoSpeak(it) },
                    enabled = ttsModelLoaded
                )
            }
        }
    }
}

internal fun LazyListScope.sttSettingsSection(
    hasSttModel: Boolean,
    sttThreads: Int,
    sttLanguage: String,
    sttDownloadStates: Map<String, ModelDownloadService.DownloadState>,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "Speech-to-Text",
            icon = TnIcons.Microphone,
            description = "Configure offline Whisper recognizer"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                // Whisper STT Model Card
                Text(
                    text = "Whisper Recognizer Model",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Glass.TextPrimary,
                    modifier = Modifier.padding(top = Standards.SpacingXs)
                )

                val downloadState = sttDownloadStates[com.bit.stt.SherpaSTTEngine.MODEL_ID]
                ModelDownloadCard(
                    title = com.bit.stt.SherpaSTTEngine.MODEL_DISPLAY_NAME,
                    description = "Fast, on-device English speech recognition · ~75 MB",
                    downloadState = downloadState,
                    onDownload = { viewModel.downloadSttModel() },
                    successText = "Installed — Ready",
                    isInstalled = hasSttModel
                )

                GlassDivider()

                // Thread count config (1-4 cores)
                Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CPU Threads",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Glass.TextPrimary
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(Standards.SpacingXs)
                        ) {
                            Text(
                                text = "$sttThreads Cores",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = Standards.SpacingSm, vertical = Standards.SpacingXxs)
                            )
                        }
                    }

                    Slider(
                        value = sttThreads.toFloat(),
                        onValueChange = { viewModel.setSttThreads(it.roundToInt()) },
                        valueRange = 1f..4f,
                        steps = 2,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Glass.Border
                        )
                    )
                }

                GlassDivider()

                // Language selection
                Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                    Text(
                        text = "Recognition Language",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Glass.TextPrimary
                    )

                    val languages = listOf("en" to "EN", "auto" to "Auto-detect")
                    ActionToggleGroup(
                        items = languages.map { it.first },
                        selectedItem = sttLanguage,
                        onItemSelected = { viewModel.setSttLanguage(it) },
                        itemLabel = { code -> languages.first { it.first == code }.second }
                    )
                }
            }
        }
    }
}
