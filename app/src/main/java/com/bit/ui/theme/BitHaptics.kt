package com.bit.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

@Stable
interface BitHaptics {
    // ── Primary Actions ──
    fun action()
    fun selection()
    fun longPress()
    fun success()
    fun reject()

    // ── Tactile Waveforms (LastChat Flagship Patterns) ──
    fun tick()
    fun pop()
    fun thud()
    fun send()
    fun buildup()
    fun dragStart()
    fun dragEnd()
    fun scrollEdge()
    fun cancel()

    // ── Generation Live Rhythm (Strictly Preserved) ──
    fun generationStart()
    fun generationTick()
    fun generationEnd()
    fun generationStopped()
    fun startAnsweringTexture()
    fun stopAnsweringTexture()
}

object NoOpBitHaptics : BitHaptics {
    override fun action() = Unit
    override fun selection() = Unit
    override fun longPress() = Unit
    override fun success() = Unit
    override fun reject() = Unit
    override fun tick() = Unit
    override fun pop() = Unit
    override fun thud() = Unit
    override fun send() = Unit
    override fun buildup() = Unit
    override fun dragStart() = Unit
    override fun dragEnd() = Unit
    override fun scrollEdge() = Unit
    override fun cancel() = Unit
    override fun generationStart() = Unit
    override fun generationTick() = Unit
    override fun generationEnd() = Unit
    override fun generationStopped() = Unit
    override fun startAnsweringTexture() = Unit
    override fun stopAnsweringTexture() = Unit
}

val LocalBitHaptics = compositionLocalOf<BitHaptics> { NoOpBitHaptics }

@Composable
fun rememberBitHaptics(enabled: Boolean = true): BitHaptics {
    val view = LocalView.current
    val enabledState = rememberUpdatedState(enabled)
    val haptics = remember(view) {
        PlatformBitHaptics(view) { enabledState.value }
    }
    DisposableEffect(haptics) {
        onDispose {
            haptics.stopAnsweringTexture()
        }
    }
    return haptics
}

private class PlatformBitHaptics(
    private val view: View,
    private val enabled: () -> Boolean
) : BitHaptics {
    private val vibrator: Vibrator? = view.context.applicationContext.findVibrator()
    private var answeringTextureActive = false

    override fun action() {
        if (!performPredefined(VibrationEffect.EFFECT_CLICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(18L, 200))
            } else {
                perform(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }

    override fun selection() {
        if (!performPredefined(VibrationEffect.EFFECT_TICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(10L, 150))
            } else {
                perform(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    override fun longPress() {
        if (!performPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(35L, 255))
            } else {
                perform(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    override fun success() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 20, 35, 25)
            val amplitudes = intArrayOf(0, 180, 0, 240)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            perform(confirmFeedback())
        }
    }

    override fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 30, 35, 30)
            val amplitudes = intArrayOf(0, 240, 0, 240)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            perform(rejectFeedback())
        }
    }

    override fun tick() = selection()

    override fun pop() {
        if (!performPredefined(VibrationEffect.EFFECT_CLICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(15L, 190))
            } else {
                perform(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    override fun thud() {
        if (!performPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(40L, 255))
            } else {
                perform(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    override fun send() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 15, 25, 35)
            val amplitudes = intArrayOf(0, 120, 0, 250)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            action()
        }
    }

    override fun buildup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 15, 15, 15, 15, 25)
            val amplitudes = intArrayOf(0, 60, 100, 150, 200, 255)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            longPress()
        }
    }

    override fun dragStart() = selection()

    override fun dragEnd() = action()

    override fun scrollEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(HapticFeedbackConstants.GESTURE_END)
        } else {
            selection()
        }
    }

    override fun cancel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(HapticFeedbackConstants.REJECT)
        } else {
            action()
        }
    }

    override fun generationStart() {
        if (!performPredefined(VibrationEffect.EFFECT_CLICK)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(20L, 220))
            } else {
                perform(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }

    private var lastTickMs = 0L

    override fun generationTick() {
        val now = System.currentTimeMillis()
        if (now - lastTickMs >= 150L) {
            lastTickMs = now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateEffect(VibrationEffect.createOneShot(8L, 100))
            } else {
                perform(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    override fun generationEnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 25, 35, 40)
            val amplitudes = intArrayOf(0, 160, 0, 220)
            vibrateEffect(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            success()
        }
    }

    override fun generationStopped() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrateEffect(VibrationEffect.createOneShot(25L, 180))
        } else {
            perform(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    override fun startAnsweringTexture() {
        if (!isAllowed() || answeringTextureActive) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        answeringTextureActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(16L, 32L),
                    intArrayOf(14, 0),
                    0
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 16L, 32L), 0)
        }
    }

    override fun stopAnsweringTexture() {
        if (!answeringTextureActive) return
        answeringTextureActive = false
        vibrator?.cancel()
    }

    private fun performPredefined(effectId: Int): Boolean {
        if (!isAllowed()) return false
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                vibrateEffect(VibrationEffect.createPredefined(effectId))
                true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun vibrateEffect(effect: VibrationEffect) {
        if (!isAllowed()) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                v.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        } catch (e: Exception) {
            // Fall back
        }
    }

    private fun perform(type: Int) {
        if (isAllowed()) {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(
                type,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    private fun isAllowed(): Boolean = enabled()

    private fun confirmFeedback(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }

    private fun rejectFeedback(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
}

private fun Context.findVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
