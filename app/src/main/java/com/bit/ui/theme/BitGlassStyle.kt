package com.bit.ui.theme

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape

object BitGlassStyle {
    val default = HazeStyle(
        tint = HazeTint(Color.White.copy(alpha = 0.06f)),
        blurRadius = 20.dp,
        noiseFactor = 0.03f
    )

    val active = HazeStyle(
        tint = HazeTint(Color.White.copy(alpha = 0.12f)),
        blurRadius = 20.dp,
        noiseFactor = 0.03f
    )

    val sheet = HazeStyle(
        tint = HazeTint(Color.White.copy(alpha = 0.08f)),
        blurRadius = 28.dp,
        noiseFactor = 0.03f
    )

    fun shouldUseGlass(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return activityManager?.isLowRamDevice == false
    }
}

fun Modifier.glassEdge(
    radius: Dp,
    borderWidth: Dp = 1.dp
): Modifier {
    return this.then(
        Modifier
            .clip(RoundedCornerShape(radius))
            .border(borderWidth, Color(0x26FFFFFF), RoundedCornerShape(radius))
    )
}
