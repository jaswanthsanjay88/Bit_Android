package com.bit.ui.components.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import com.bit.ui.components.renderMathToUnicode

object LatexRenderer {

    /**
     * Renders LaTeX expression to a Bitmap by using MathRenderer's Unicode layout
     * painted onto a crisp Android Bitmap.
     */
    fun renderToBitmap(
        context: Context,
        latex: String,
        isDisplay: Boolean,
        textColor: Color
    ): Bitmap {
        val unicodeText = renderMathToUnicode(latex)
        val textSizePx = if (isDisplay) 48f else 36f

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor.toArgb()
            textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        }

        val padding = if (isDisplay) 24 else 12
        val textWidth = Math.max(1, Math.ceil(paint.measureText(unicodeText).toDouble()).toInt())

        val bounds = android.graphics.Rect()
        paint.getTextBounds(unicodeText, 0, unicodeText.length, bounds)
        val textHeight = Math.max(1, bounds.height() + padding)

        val bitmap = Bitmap.createBitmap(textWidth + padding * 2, textHeight + padding, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawText(unicodeText, padding.toFloat(), (textHeight - bounds.bottom).toFloat(), paint)
        return bitmap
    }
}

/**
     * ImageTransformer implementation for com.mikepenz.markdown.compose.
     * Intercepts `latex://` links and transforms them into BitmapPainters.
     */
@Composable
fun rememberLatexImageTransformer(
    context: Context,
    textColor: Color
): (String) -> Painter? {
    return remember(context, textColor) {
        { url ->
            if (url.startsWith(MarkdownPreprocessor.LATEX_URL_PREFIX)) {
                val decoded = MarkdownPreprocessor.decodeLatexUrl(url)
                if (decoded != null) {
                    val (latex, isDisplay) = decoded
                    val bitmap = LatexRenderer.renderToBitmap(context, latex, isDisplay, textColor)
                    BitmapPainter(bitmap.asImageBitmap())
                } else null
            } else null
        }
    }
}
