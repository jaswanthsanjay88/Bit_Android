package com.bit.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RevealDrawerState(
    initialValue: Boolean = false,
    val maxOffsetPx: Float,
    private val coroutineScope: CoroutineScope
) {
    val offsetX = Animatable(if (initialValue) maxOffsetPx else 0f)

    val isOpen: Boolean
        get() = offsetX.value > maxOffsetPx / 2

    fun open() {
        coroutineScope.launch {
            offsetX.animateTo(maxOffsetPx, spring(dampingRatio = 0.8f, stiffness = 300f))
        }
    }

    fun close() {
        coroutineScope.launch {
            offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 300f))
        }
    }

    fun toggle() {
        if (isOpen) close() else open()
    }
}

@Composable
fun rememberRevealDrawerState(
    initialValue: Boolean = false,
    maxOffsetPx: Float,
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): RevealDrawerState {
    return remember(maxOffsetPx) {
        RevealDrawerState(initialValue, maxOffsetPx, coroutineScope)
    }
}
