package com.bit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class CadenceStyle { ADAPTIVE, FIXED }

/**
 * Visual Cadence Indicator:
 * - FIXED: Static, evenly spaced ticks representing scheduled timers (e.g. "every 2 hours").
 * - ADAPTIVE: Dynamic tick density representing delta-triggered watchers (e.g. stock/score watchers).
 */
@Composable
fun CadenceIndicator(
    intervalMs: Long,
    isActive: Boolean = true,
    cadenceStyle: CadenceStyle = CadenceStyle.FIXED,
    modifier: Modifier = Modifier
) {
    val tickCount = when {
        intervalMs <= 60_000L -> 12
        intervalMs <= 300_000L -> 8
        else -> 5
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until tickCount) {
            val isLastTick = i == tickCount - 1
            val heightDp = if (isLastTick && isActive && cadenceStyle == CadenceStyle.ADAPTIVE) 10.dp else 6.dp
            val alpha = if (cadenceStyle == CadenceStyle.FIXED) {
                if (isActive) 0.5f else 0.2f
            } else {
                if (isLastTick && isActive) 1.0f else 0.3f
            }

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(heightDp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            )
        }
    }
}
