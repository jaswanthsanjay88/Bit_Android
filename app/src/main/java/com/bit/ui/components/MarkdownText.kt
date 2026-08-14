package com.bit.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.MapleMonoFontFamily
import com.bit.global.Standards

// ── Backward-compat composition local ──

val LocalCodeHighlightEnabled = compositionLocalOf { true }

/**
 * Colors extracted once from MaterialTheme — passed to non-composable formatters
 * to avoid reading theme state inside every Text().
 */
@Immutable
data class InlineColors(
    val codeBg: Color,
    val highlightBg: Color,
    val mathColor: Color
)

/**
 * Full markdown renderer for completed (non-streaming) messages.
 * Parses text into elements and renders each with appropriate styling.
 * Result is cached by [text] — stable for completed messages.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val processedText = remember(text) { 
        com.bit.ui.components.markdown.MarkdownPreprocessor.toRenderableMarkdownText(text) 
    }
    val parsedContent = remember(processedText) { parseMarkdown(processedText) }
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val colors = remember(surfaceVariant, primary) {
        InlineColors(
            codeBg = surfaceVariant.copy(alpha = 0.5f),
            highlightBg = primary.copy(alpha = 0.3f),
            mathColor = primary
        )
    }
    Column(
        modifier = modifier.padding(horizontal = Standards.SpacingXs),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
    ) {
        parsedContent.forEach { element -> MarkdownElementView(element, colors) }
    }
}

/**
 * Lazy version — each markdown element is a separate LazyList item.
 * Only visible items are composed. Use inside a LazyColumn.
 * Element-aware spacing: headings get more top padding, blocks get breathing room.
 */
fun LazyListScope.lazyMarkdownItems(
    text: String,
    keyPrefix: String,
    modifier: Modifier = Modifier
) {
    val elements = parseMarkdownCached(text)
    items(
        count = elements.size,
        key = { index -> "${keyPrefix}-md-$index" },
        contentType = { index -> elements[index]::class.simpleName }
    ) { index ->
        val element = elements[index]
        val scheme = MaterialTheme.colorScheme
        val colors = remember(scheme) {
            InlineColors(
                codeBg = scheme.surfaceVariant.copy(alpha = 0.5f),
                highlightBg = scheme.primary.copy(alpha = 0.3f),
                mathColor = scheme.primary
            )
        }
        Box(modifier = modifier.padding(
            top = element.topSpacing(),
            bottom = element.bottomSpacing()
        )) {
            MarkdownElementView(element, colors)
        }
    }
}

/** Spacing above — headings get more to create visual section breaks. */
private fun MarkdownElement.topSpacing(): Dp = when (this) {
    is MarkdownElement.Heading1 -> 14.dp
    is MarkdownElement.Heading2 -> 12.dp
    is MarkdownElement.Heading3 -> 10.dp
    is MarkdownElement.Heading4, is MarkdownElement.Heading5, is MarkdownElement.Heading6 -> 8.dp
    is MarkdownElement.CodeBlock, is MarkdownElement.Table, is MarkdownElement.MathBlock -> 6.dp
    is MarkdownElement.Quote -> 4.dp
    is MarkdownElement.Divider -> 8.dp
    is MarkdownElement.BulletPoint, is MarkdownElement.NumberedPoint -> 1.dp
    else -> 2.dp
}

/** Spacing below — content elements get less so they group with following items. */
private fun MarkdownElement.bottomSpacing(): Dp = when (this) {
    is MarkdownElement.Heading1, is MarkdownElement.Heading2, is MarkdownElement.Heading3 -> 3.dp
    is MarkdownElement.Heading4, is MarkdownElement.Heading5, is MarkdownElement.Heading6 -> 2.dp
    is MarkdownElement.CodeBlock, is MarkdownElement.Table, is MarkdownElement.MathBlock -> 6.dp
    is MarkdownElement.Quote -> 4.dp
    is MarkdownElement.Divider -> 8.dp
    is MarkdownElement.BulletPoint, is MarkdownElement.NumberedPoint -> 1.dp
    else -> 2.dp
}

// ── Sealed element model ──

internal sealed class MarkdownElement {
    data class Heading1(val text: String) : MarkdownElement()
    data class Heading2(val text: String) : MarkdownElement()
    data class Heading3(val text: String) : MarkdownElement()
    data class Heading4(val text: String) : MarkdownElement()
    data class Heading5(val text: String) : MarkdownElement()
    data class Heading6(val text: String) : MarkdownElement()
    data class Body(val text: String) : MarkdownElement()
    data class BulletPoint(val text: String, val level: Int = 0) : MarkdownElement()
    data class NumberedPoint(val text: String, val number: String) : MarkdownElement()
    data class Quote(val text: String, val level: Int = 1) : MarkdownElement()
    data class CodeBlock(val code: String, val language: String) : MarkdownElement()
    data class InlineCode(val text: String) : MarkdownElement()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<Alignment>
    ) : MarkdownElement() {
        enum class Alignment { LEFT, CENTER, RIGHT }
    }
    data class MathBlock(val expression: String, val isTypst: Boolean = false) : MarkdownElement()
    data class InlineMath(val expression: String, val isTypst: Boolean = false) : MarkdownElement()
    data object Divider : MarkdownElement()
}

// ── Parser cache & precompiled regex ──

private val parseCache = java.util.concurrent.ConcurrentHashMap<String, List<MarkdownElement>>(16)

private fun parseMarkdownCached(text: String): List<MarkdownElement> {
    parseCache[text]?.let { return it }
    synchronized(parseCache) {
        parseCache[text]?.let { return it }
        if (parseCache.size >= 16) {
            val keysToRemove = parseCache.keys.take(parseCache.size / 2)
            keysToRemove.forEach { parseCache.remove(it) }
        }
        return parseMarkdown(text).also { parseCache[text] = it }
    }
}

private val BULLET_REGEX = Regex("^\\s*[+\\-*]\\s+.+")
private val NUMBERED_REGEX = Regex("^\\d+\\.\\s+.+")
private val TABLE_SEP_REGEX = Regex("^:?-{1,}:?$")
private val LATEX_BEGIN_REGEX = Regex("""\\{1,2}begin\s*\{(equation|align|gather|multline|displaymath|math)\*?\}""")
private val LATEX_NORM_FIX = Regex("""\\begin\s+\{""")
private val LATEX_ENV_REGEX = Regex("""\\begin\{(equation|align|gather|multline|displaymath|math)(\*?)\}""")

fun sanitizeForDisplay(raw: String): String =
    raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")

// ── Parser ──

internal fun parseMarkdown(text: String): List<MarkdownElement> {
    val cleanText = sanitizeForDisplay(text)
    val elements = mutableListOf<MarkdownElement>()
    val lines = cleanText.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        when {
            // Block math: \[...\]
            line.trimStart().startsWith("\\[") || line.trimStart().startsWith("\\\\[") -> {
                val isDouble = line.contains("\\\\[")
                val startPat = if (isDouble) "\\\\[" else "\\["
                val startCol = line.indexOf(startPat)
                val after = line.substring(startCol + startPat.length)
                val endSingle = "\\]"; val endDouble = "\\\\]"
                val sameEnd = when {
                    after.contains(endDouble) -> after.indexOf(endDouble)
                    after.contains(endSingle) -> after.indexOf(endSingle)
                    else -> -1
                }
                if (sameEnd != -1) {
                    elements.add(MarkdownElement.MathBlock(after.substring(0, sameEnd).trim().replace("\\\\", "\\"), false))
                } else {
                    val mathLines = mutableListOf<String>()
                    if (after.isNotBlank()) mathLines.add(after.replace("\\\\", "\\"))
                    i++
                    while (i < lines.size && !lines[i].contains(endSingle) && !lines[i].contains(endDouble)) {
                        mathLines.add(lines[i].replace("\\\\", "\\")); i++
                    }
                    if (i < lines.size) {
                        val cl = lines[i]
                        val ci = if (cl.contains(endDouble)) cl.indexOf(endDouble) else cl.indexOf(endSingle)
                        if (ci > 0) mathLines.add(cl.substring(0, ci).replace("\\\\", "\\"))
                    }
                    elements.add(MarkdownElement.MathBlock(mathLines.joinToString("\n").trim(), false))
                }
            }

            // LaTeX math environments
            LATEX_BEGIN_REGEX.containsMatchIn(line) -> {
                val norm = line.replace("\\\\", "\\").replace(LATEX_NORM_FIX, "\\begin{")
                val envMatch = LATEX_ENV_REGEX.find(norm)
                val envName = envMatch?.groupValues?.get(1) ?: "equation"
                val starred = envMatch?.groupValues?.get(2) ?: ""
                val endRx = Regex("""\\{1,2}end\s*\{${Regex.escape(envName)}${Regex.escape(starred)}\}""")
                val mathLines = mutableListOf<String>()
                i++
                while (i < lines.size && !endRx.containsMatchIn(lines[i])) {
                    mathLines.add(lines[i].replace("\\\\", "\\")); i++
                }
                val expr = mathLines.joinToString("\n").trim()
                if (expr.isNotBlank()) elements.add(MarkdownElement.MathBlock(expr, false))
            }

            // Block math: $$...$$
            line.trimStart().startsWith("$$") -> {
                val startCol = line.indexOf("$$")
                val after = line.substring(startCol + 2)
                val sameEnd = after.indexOf("$$")
                if (sameEnd != -1) {
                    val expr = after.substring(0, sameEnd).trim().replace("\\\\", "\\")
                    elements.add(MarkdownElement.MathBlock(expr, expr.contains("#")))
                } else {
                    val mathLines = mutableListOf<String>()
                    if (after.isNotBlank()) mathLines.add(after.replace("\\\\", "\\"))
                    i++
                    while (i < lines.size && !lines[i].contains("$$")) {
                        mathLines.add(lines[i].replace("\\\\", "\\")); i++
                    }
                    val expr = mathLines.joinToString("\n").trim()
                    elements.add(MarkdownElement.MathBlock(expr, expr.contains("#")))
                }
            }

            // Fenced code block
            line.startsWith("```") -> {
                val language = line.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) { codeLines.add(lines[i]); i++ }
                elements.add(MarkdownElement.CodeBlock(codeLines.joinToString("\n"), language))
            }

            // Indented code block
            line.trimStart().startsWith("    ") && line.trim().isNotEmpty() -> {
                val codeLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("    ") || lines[i].isBlank())) {
                    if (lines[i].trim().isNotEmpty()) codeLines.add(lines[i].removePrefix("    "))
                    i++
                }
                i--
                elements.add(MarkdownElement.CodeBlock(codeLines.joinToString("\n"), ""))
            }

            // Headings
            line.startsWith("###### ") -> elements.add(MarkdownElement.Heading6(line.removePrefix("###### ")))
            line.startsWith("##### ") -> elements.add(MarkdownElement.Heading5(line.removePrefix("##### ")))
            line.startsWith("#### ") -> elements.add(MarkdownElement.Heading4(line.removePrefix("#### ")))
            line.startsWith("### ") -> elements.add(MarkdownElement.Heading3(line.removePrefix("### ")))
            line.startsWith("## ") -> elements.add(MarkdownElement.Heading2(line.removePrefix("## ")))
            line.startsWith("# ") -> elements.add(MarkdownElement.Heading1(line.removePrefix("# ")))

            // Bullet points
            line.matches(BULLET_REGEX) -> {
                val level = line.takeWhile { it == ' ' }.length / 2
                elements.add(MarkdownElement.BulletPoint(line.trimStart().substring(2), level))
            }

            // Numbered lists
            line.matches(NUMBERED_REGEX) -> {
                elements.add(MarkdownElement.NumberedPoint(line.substringAfter(". "), line.substringBefore(".")))
            }

            // Block quotes
            line.startsWith(">") -> {
                val level = line.takeWhile { it == '>' }.length
                elements.add(MarkdownElement.Quote(line.substring(level).trim(), level))
            }

            // Table
            line.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                val headers = parseTableRow(line)
                i++ // skip to separator
                val alignments = lines[i].split("|").filter { it.isNotBlank() }.map { cell ->
                    val t = cell.trim()
                    when {
                        t.startsWith(":") && t.endsWith(":") -> MarkdownElement.Table.Alignment.CENTER
                        t.endsWith(":") -> MarkdownElement.Table.Alignment.RIGHT
                        else -> MarkdownElement.Table.Alignment.LEFT
                    }
                }
                i++
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val row = parseTableRow(lines[i])
                    val normalized = when {
                        row.size < headers.size -> row + List(headers.size - row.size) { "" }
                        row.size > headers.size -> row.take(headers.size)
                        else -> row
                    }
                    rows.add(normalized); i++
                }
                i--
                val finalAlignments = when {
                    alignments.size < headers.size -> alignments + List(headers.size - alignments.size) { MarkdownElement.Table.Alignment.LEFT }
                    alignments.size > headers.size -> alignments.take(headers.size)
                    else -> alignments
                }
                elements.add(MarkdownElement.Table(headers, rows, finalAlignments))
            }

            // Divider
            line == "---" || line == "___" || line == "***" -> elements.add(MarkdownElement.Divider)

            // Body text
            line.isNotBlank() -> elements.add(MarkdownElement.Body(line))
        }
        i++
    }
    return elements
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return false
    val cells = trimmed.split("|").filter { it.isNotBlank() }
    return cells.isNotEmpty() && cells.all { it.trim().matches(TABLE_SEP_REGEX) }
}

private fun parseTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

// ── Inline formatting ──

/**
 * Find closing ** for bold, accounting for *** (italic close + bold close).
 * When a run of 3+ stars is found, the last 2 close bold, any preceding ones close italic.
 */
private fun findStarClose(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == '*') {
            var end = i
            while (end < text.length && text[end] == '*') end++
            if (end - i >= 2) return end - 2
            i = end
        } else i++
    }
    return -1
}

/** Pure function — no @Composable, no MaterialTheme reads. */
internal fun buildInlineFormatted(text: String, colors: InlineColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    val chars = text.toCharArray()
    while (i < chars.size) {
        when {
            // Bold+Italic ***...***
            i + 2 < chars.size && chars[i] == '*' && chars[i + 1] == '*' && chars[i + 2] == '*' -> {
                val end = text.indexOf("***", i + 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(buildInlineFormatted(text.substring(i + 3, end), colors))
                    }; i = end + 3
                } else { append(chars[i]); i++ }
            }
            // Bold **...**
            i + 1 < chars.size && chars[i] == '*' && chars[i + 1] == '*' -> {
                val end = findStarClose(text, i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(buildInlineFormatted(text.substring(i + 2, end), colors))
                    }; i = end + 2
                } else { append(chars[i]); i++ }
            }
            // Bold __...__
            i + 1 < chars.size && chars[i] == '_' && chars[i + 1] == '_' -> {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(buildInlineFormatted(text.substring(i + 2, end), colors))
                    }; i = end + 2
                } else { append(chars[i]); i++ }
            }
            // Italic *...*
            chars[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(buildInlineFormatted(text.substring(i + 1, end), colors))
                    }; i = end + 1
                } else { append(chars[i]); i++ }
            }
            // Italic _..._
            chars[i] == '_' -> {
                val end = text.indexOf('_', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(buildInlineFormatted(text.substring(i + 1, end), colors))
                    }; i = end + 1
                } else { append(chars[i]); i++ }
            }
            // Strikethrough ~~...~~
            i + 1 < chars.size && chars[i] == '~' && chars[i + 1] == '~' -> {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(buildInlineFormatted(text.substring(i + 2, end), colors))
                    }; i = end + 2
                } else { append(chars[i]); i++ }
            }
            // Inline code `...`
            chars[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = MapleMonoFontFamily, background = colors.codeBg, fontSize = 12.sp)) {
                        append(" ${text.substring(i + 1, end)} ")
                    }; i = end + 1
                } else { append(chars[i]); i++ }
            }
            // Highlight ==...==
            i + 1 < chars.size && chars[i] == '=' && chars[i + 1] == '=' -> {
                val end = text.indexOf("==", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(background = colors.highlightBg)) {
                        append(buildInlineFormatted(text.substring(i + 2, end), colors))
                    }; i = end + 2
                } else { append(chars[i]); i++ }
            }
            // Inline math \(...\)
            i + 1 < chars.size && chars[i] == '\\' && chars[i + 1] == '(' -> {
                val endIdx = text.indexOf("\\)", i + 2)
                if (endIdx != -1) {
                    val rendered = renderMathToUnicode(text.substring(i + 2, endIdx))
                    withStyle(SpanStyle(fontFamily = MapleMonoFontFamily, fontStyle = FontStyle.Italic, color = colors.mathColor)) { append(rendered) }
                    i = endIdx + 2
                } else { append(chars[i]); i++ }
            }
            // Inline math $...$
            chars[i] == '$' && (i + 1 >= chars.size || chars[i + 1] != '$') -> {
                val end = text.indexOf('$', i + 1)
                if (end != -1 && end > i + 1) {
                    val rendered = renderMathToUnicode(text.substring(i + 1, end))
                    withStyle(SpanStyle(fontFamily = MapleMonoFontFamily, fontStyle = FontStyle.Italic, color = colors.mathColor)) { append(rendered) }
                    i = end + 1
                } else { append(chars[i]); i++ }
            }
            // Citation `[id]`
            chars[i] == '[' && i + 5 < chars.size && chars[i + 5] == ']' -> {
                val citation = text.substring(i + 1, i + 5)
                if (citation.all { it.isLetterOrDigit() }) {
                    withStyle(SpanStyle(
                        fontSize = 10.sp,
                        color = colors.mathColor,
                        background = colors.codeBg,
                        baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
                        fontWeight = FontWeight.Bold
                    )) {
                        append("[$citation]")
                    }
                    i += 6
                } else {
                    append(chars[i])
                    i++
                }
            }
            // Default — handle surrogates
            else -> {
                val c = chars[i]
                if (c.isHighSurrogate() && i + 1 < chars.size && chars[i + 1].isLowSurrogate()) {
                    append(c); append(chars[i + 1]); i += 2
                } else { append(c); i++ }
            }
        }
    }
}

@Composable
private fun cachedInlineFormatting(text: String, colors: InlineColors): AnnotatedString =
    remember(text, colors) { buildInlineFormatted(text, colors) }

// ── Element renderers ──

@Composable
private fun MarkdownElementView(element: MarkdownElement, colors: InlineColors) {
    when (element) {
        is MarkdownElement.Heading1 -> HeadingText(element.text, colors, 24.sp, FontWeight.Bold, 4.dp)
        is MarkdownElement.Heading2 -> HeadingText(element.text, colors, 20.sp, FontWeight.SemiBold, 3.dp)
        is MarkdownElement.Heading3 -> HeadingText(element.text, colors, 17.sp, FontWeight.SemiBold, 2.dp)
        is MarkdownElement.Heading4 -> HeadingText(element.text, colors, 15.sp, FontWeight.Medium, 2.dp)
        is MarkdownElement.Heading5 -> HeadingText(element.text, colors, 14.sp, FontWeight.Medium, 1.dp)
        is MarkdownElement.Heading6 -> HeadingText(element.text, colors, 13.sp, FontWeight.Medium, 1.dp, 0.87f)
        is MarkdownElement.Body -> BodyText(element.text, colors)
        is MarkdownElement.BulletPoint -> BulletPointView(element.text, element.level, colors)
        is MarkdownElement.NumberedPoint -> NumberedPointView(element.text, element.number, colors)
        is MarkdownElement.Quote -> BlockQuoteView(element.text, element.level, colors)
        is MarkdownElement.CodeBlock -> CodeBlockView(element.code, element.language)
        is MarkdownElement.InlineCode -> InlineCodeView(element.text)
        is MarkdownElement.Table -> TableView(element.headers, element.rows, element.alignments, colors)
        is MarkdownElement.MathBlock -> MathBlockView(element.expression, element.isTypst)
        is MarkdownElement.InlineMath -> InlineMathView(element.expression)
        is MarkdownElement.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = Standards.SpacingXs),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun HeadingText(
    text: String, colors: InlineColors,
    fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight,
    verticalPad: androidx.compose.ui.unit.Dp, alpha: Float = 1f
) {
    Text(
        text = cachedInlineFormatting(text, colors),
        style = MaterialTheme.typography.titleLarge,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = LocalContentColor.current.copy(alpha = alpha),
        modifier = Modifier.padding(vertical = verticalPad)
    )
}

@Composable
private fun BodyText(text: String, colors: InlineColors) {
    Text(
        text = cachedInlineFormatting(text, colors),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalContentColor.current.copy(alpha = 0.87f),
        lineHeight = 20.sp
    )
}

@Composable
private fun BulletPointView(text: String, level: Int, colors: InlineColors) {
    Row(
        modifier = Modifier.padding(start = (4 + level * 12).dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = cachedInlineFormatting(text, colors),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.87f),
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NumberedPointView(text: String, number: String, colors: InlineColors) {
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = cachedInlineFormatting(text, colors),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.87f),
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BlockQuoteView(text: String, level: Int, colors: InlineColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((level - 1) * 10).dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(Standards.SpacingSm),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = cachedInlineFormatting(text, colors),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.87f),
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun InlineCodeView(text: String) {
    Text(
        text = text,
        fontFamily = MapleMonoFontFamily,
        fontSize = 12.sp,
        color = LocalContentColor.current.copy(alpha = 0.85f),
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// ── Code block (collapsed by default) ──

@Composable
private fun CodeBlockView(code: String, language: String) {
    var isExpanded by remember(code) {
        val lineCount = code.count { it == '\n' } + 1
        mutableStateOf(lineCount <= 12)
    }
    val context = LocalContext.current

    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val headerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = contentColor.copy(alpha = 0.6f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val shape = RoundedCornerShape(14.dp)
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingXs)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (language.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(contentColor.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = language.uppercase(),
                            fontFamily = MapleMonoFontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            color = mutedColor
                        )
                    }
                }
                Text(
                    text = "$lineCount ${if (lineCount == 1) "line" else "lines"}",
                    fontSize = 10.sp,
                    color = contentColor.copy(alpha = 0.4f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton(
                    onClickListener = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(language, code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    icon = TnIcons.Copy,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = mutedColor
                    )
                )
                if (lineCount > 12) {
                    ActionToggleButton(
                        checked = isExpanded,
                        onCheckedChange = { isExpanded = !isExpanded },
                        icon = if (isExpanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = mutedColor,
                            checkedContainerColor = contentColor.copy(alpha = 0.08f),
                            checkedContentColor = contentColor.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column {
                HorizontalDivider(color = borderColor)
                val syntaxTheme = resolveSyntaxTheme()
                val highlighted = remember(code, language) {
                    if (language.isNotBlank()) highlightCode(code, language, syntaxTheme) else null
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = highlighted ?: AnnotatedString(code),
                        fontFamily = MapleMonoFontFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = contentColor.copy(alpha = 0.92f)
                    )
                }
            }
        }

        if (!isExpanded) {
            Text(
                text = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "",
                fontFamily = MapleMonoFontFamily,
                fontSize = 12.sp,
                color = contentColor.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

// ── Canvas-drawn table ──

@Composable
private fun TableView(
    headers: List<String>,
    rows: List<List<String>>,
    alignments: List<MarkdownElement.Table.Alignment>,
    colors: InlineColors
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingSm)
    ) {
        val colCount = headers.size.coerceAtLeast(1)

        if (colCount == 2 && maxWidth < 360.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rows.forEach { rowCells ->
                    val topic = rowCells.getOrNull(0) ?: ""
                    val keyPoint = rowCells.getOrNull(1) ?: ""
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = cachedInlineFormatting(topic, colors),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = cachedInlineFormatting(keyPoint, colors),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return@BoxWithConstraints
        }

        val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
        val bodyBg = MaterialTheme.colorScheme.surfaceContainerHigh
        val altRowBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
        val textColor = MaterialTheme.colorScheme.onSurface
        val cellTextColor = textColor.copy(alpha = 0.85f)
        val density = LocalDensity.current

        val cellPadH = with(density) { 12.dp.toPx() }
        val cellPadV = with(density) { 10.dp.toPx() }
        val rowDividerWidth = with(density) { 1.dp.toPx() }
        val minColWidth = with(density) { 72.dp.toPx() }
        val maxColWidth = with(density) { 240.dp.toPx() }
        val textMeasurer = rememberTextMeasurer()

        val headerStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor,
            lineHeight = 17.sp, letterSpacing = 0.2.sp
        )
        val cellStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp, color = cellTextColor, lineHeight = 17.sp
        )

        val naturalWidths = remember(headers, rows) {
            List(colCount) { colIndex ->
                val headerW = textMeasurer.measure(
                    buildInlineFormatted(headers.getOrElse(colIndex) { "" }, colors), style = headerStyle
                ).size.width
                val cellsW = rows.maxOfOrNull { row ->
                    textMeasurer.measure(
                        buildInlineFormatted(row.getOrElse(colIndex) { "" }, colors), style = cellStyle
                    ).size.width
                } ?: 0
                (maxOf(headerW, cellsW) + cellPadH * 2).coerceIn(minColWidth, maxColWidth)
            }
        }

        val availableWidth = constraints.maxWidth.toFloat()
        val naturalTotal = naturalWidths.sum()

        val colWidths = if (naturalTotal <= availableWidth) {
            val extra = availableWidth - naturalTotal
            val stretchIndex = naturalWidths.indices.maxByOrNull { naturalWidths[it] } ?: 0
            naturalWidths.mapIndexed { i, w -> if (i == stretchIndex) w + extra else w }
        } else {
            naturalWidths
        }
        val tableWidth = colWidths.sum()

        val headerMeasured = remember(headers, colWidths) {
            headers.mapIndexed { i, h ->
                textMeasurer.measure(
                    buildInlineFormatted(h, colors), style = headerStyle,
                    constraints = androidx.compose.ui.unit.Constraints(
                        maxWidth = (colWidths[i] - cellPadH * 2).coerceAtLeast(0f).toInt()
                    )
                )
            }
        }
        val headerRowHeight = (headerMeasured.maxOfOrNull { it.size.height } ?: 0) + cellPadV * 2

        val rowsMeasured = remember(rows, colWidths) {
            rows.map { row ->
                row.mapIndexed { i, cell ->
                    textMeasurer.measure(
                        buildInlineFormatted(cell, colors), style = cellStyle,
                        constraints = androidx.compose.ui.unit.Constraints(
                            maxWidth = (colWidths.getOrElse(i) { colWidths.last() } - cellPadH * 2).coerceAtLeast(0f).toInt()
                        )
                    )
                }
            }
        }
        val rowHeights = rowsMeasured.map { row -> (row.maxOfOrNull { it.size.height } ?: 0) + cellPadV * 2 }
        val totalHeight = headerRowHeight + rowDividerWidth * rows.size + rowHeights.sum()
        val tableShape = RoundedCornerShape(14.dp)

        val canvasContent: @Composable () -> Unit = {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .width(with(density) { tableWidth.toDp() })
                    .height(with(density) { totalHeight.toDp() })
                    .clip(tableShape)
                    .border(1.dp, outlineColor, tableShape)
                    .drawBehind {
                        drawRect(color = bodyBg, size = Size(tableWidth, totalHeight))
                        drawRect(color = headerBg, size = Size(tableWidth, headerRowHeight))
                        drawMultiColumnRow(headerMeasured, colWidths, cellPadH, 0f, headerRowHeight, alignments)

                        drawLine(
                            color = outlineColor,
                            start = Offset(0f, headerRowHeight),
                            end = Offset(tableWidth, headerRowHeight),
                            strokeWidth = rowDividerWidth
                        )

                        var y = headerRowHeight + rowDividerWidth
                        rowsMeasured.forEachIndexed { rowIndex, cells ->
                            val rh = rowHeights[rowIndex]
                            if (rowIndex % 2 == 1) {
                                drawRect(color = altRowBg, topLeft = Offset(0f, y), size = Size(tableWidth, rh))
                            }
                            drawMultiColumnRow(cells, colWidths, cellPadH, y, rh, alignments)
                            y += rh
                            if (rowIndex != rowsMeasured.lastIndex) {
                                drawLine(
                                    color = outlineColor,
                                    start = Offset(0f, y),
                                    end = Offset(tableWidth, y),
                                    strokeWidth = rowDividerWidth
                                )
                                y += rowDividerWidth
                            }
                        }
                    }
            ) {}
        }

        if (naturalTotal > availableWidth) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { canvasContent() }
        } else {
            canvasContent()
        }
    }
}

private fun DrawScope.drawMultiColumnRow(
    measuredCells: List<androidx.compose.ui.text.TextLayoutResult>,
    colWidths: List<Float>,
    padH: Float,
    top: Float,
    rowHeight: Float,
    alignments: List<MarkdownElement.Table.Alignment>
) {
    var x = 0f
    measuredCells.forEachIndexed { i, layout ->
        val colWidth = colWidths.getOrElse(i) { colWidths.last() }
        val align = alignments.getOrElse(i) { MarkdownElement.Table.Alignment.LEFT }
        val textX = when (align) {
            MarkdownElement.Table.Alignment.CENTER -> x + (colWidth - layout.size.width) / 2f
            MarkdownElement.Table.Alignment.RIGHT -> x + colWidth - layout.size.width - padH
            else -> x + padH
        }
        val textY = top + (rowHeight - layout.size.height) / 2f
        drawText(layout, topLeft = Offset(textX.coerceAtLeast(x), textY))
        x += colWidth
    }
}

// ── Math views ──

@Composable
private fun MathBlockView(expression: String, isTypst: Boolean) {
    val renderedMath = remember(expression) { renderMathToUnicode(expression) }
    val mathColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "\u2211", fontFamily = MapleMonoFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = mathColor)
            Text(
                text = if (isTypst) "TYPST" else "MATH",
                fontFamily = MapleMonoFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Box(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = renderedMath,
                fontFamily = MapleMonoFontFamily, fontSize = 16.sp,
                fontStyle = FontStyle.Italic,
                color = LocalContentColor.current, lineHeight = 24.sp
            )
        }
    }
}

@Composable
private fun InlineMathView(expression: String) {
    val renderedMath = remember(expression) { renderMathToUnicode(expression) }
    Text(
        text = renderedMath,
        fontFamily = MapleMonoFontFamily,
        fontSize = 14.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// ── Util ──

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
