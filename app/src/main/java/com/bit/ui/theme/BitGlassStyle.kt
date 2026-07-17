package com.bit.ui.theme

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object BitGlassStyle {
    fun shouldUseGlass(context: Context): Boolean {
        return false
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
