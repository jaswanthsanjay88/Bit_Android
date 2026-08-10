package com.bit.ui.theme

import android.content.Context
import android.os.Build
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
    fun action()
    fun selection()
    fun longPress()
    fun success()
    fun reject()
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
            perform(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    override fun selection() {
        if (!performPredefined(VibrationEffect.EFFECT_TICK)) {
            perform(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    override fun success() = perform(confirmFeedback())

    override fun reject() = perform(rejectFeedback())

    override fun generationStart() {
        if (!performPredefined(VibrationEffect.EFFECT_CLICK)) {
            perform(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private var lastTickMs = 0L

    override fun generationTick() {
        val now = System.currentTimeMillis()
        if (now - lastTickMs >= 150L) {
            lastTickMs = now
            if (!performPredefined(VibrationEffect.EFFECT_TICK)) {
                perform(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
    }

    override fun generationEnd() {
        if (isAllowed()) {
            val vibrator = vibrator?.takeIf { it.hasVibrator() }
            if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 25, 35, 40)
                val amplitudes = intArrayOf(0, 160, 0, 220)
                try {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    return
                } catch (e: Exception) {
                    // Fall back
                }
            }
            success()
        }
    }

    override fun generationStopped() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    override fun startAnsweringTexture() {
        if (!isAllowed() || answeringTextureActive) return
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        answeringTextureActive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(16L, 32L),
                    intArrayOf(12, 0),
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
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
                true
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun perform(type: Int) {
        if (isAllowed()) {
            view.performHapticFeedback(type)
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
