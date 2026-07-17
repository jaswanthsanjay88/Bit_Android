package com.bit.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.bit.ui.theme.BitColors

/**
 * Lightweight GitHub-flavoured Markdown → AnnotatedString renderer.
 * Handles the subset of Markdown commonly found in GitHub release bodies:
 *   - Headings (##, ###)
 *   - Bold (**text**)
 *   - Inline code (`code`)
 *   - Bullet lists (- item)
 *   - Links [text](url) (rendered as underlined, non-clickable for now)
 *   - Blank-line spacing
 *
 * Does NOT attempt to be a full CommonMark parser — just enough to make
 * changelogs readable without pulling in a library.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = BitColors.TextPrimary,
    secondaryColor: Color = BitColors.TextSecondary
) {
    val annotated = remember(markdown, color, secondaryColor) {
        parseMarkdown(markdown, color, secondaryColor)
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color)
    )
}

/**
 * Parses a raw Markdown string into an [AnnotatedString] with styled spans.
 */
private fun parseMarkdown(
    raw: String,
    baseColor: Color,
    secondaryColor: Color
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = baseColor)) {
        val lines = raw.lines()
        var prevBlank = false

    for ((index, rawLine) in lines.withIndex()) {
        val line = rawLine.trimEnd()

        // Skip empty lines but insert spacing
        if (line.isBlank()) {
            prevBlank = true
            continue
        }

        // Add spacing between blocks
        if (index > 0 && prevBlank) {
            append("\n\n")
        } else if (index > 0) {
            append("\n")
        }
        prevBlank = false

        when {
            // ## Heading 2
            line.startsWith("## ") -> {
                withStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = baseColor
                )) {
                    appendInlineMarkdown(line.removePrefix("## ").trim(), baseColor, secondaryColor)
                }
            }

            // ### Heading 3
            line.startsWith("### ") -> {
                withStyle(SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = baseColor
                )) {
                    appendInlineMarkdown(line.removePrefix("### ").trim(), baseColor, secondaryColor)
                }
            }

            // - Bullet item
            line.trimStart().startsWith("- ") -> {
                val indent = line.length - line.trimStart().length
                val bulletIndent = "  ".repeat(indent / 2)
                append("$bulletIndent• ")
                withStyle(SpanStyle(color = baseColor)) {
                    appendInlineMarkdown(line.trimStart().removePrefix("- "), baseColor, secondaryColor)
                }
            }

            // | Table rows — render as-is but dimmed
            line.trimStart().startsWith("|") -> {
                // Skip separator rows like |---|---|
                if (line.contains("---")) return@buildAnnotatedString
                val cells = line.split("|")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                withStyle(SpanStyle(color = secondaryColor, fontSize = 12.sp)) {
                    append(cells.joinToString("  ·  "))
                }
            }

            // Regular paragraph text
            else -> {
                appendInlineMarkdown(line, baseColor, secondaryColor)
            }
        }
    }
}
}

/**
 * Processes inline Markdown within a single line: **bold**, `code`, [link](url).
 */
private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    baseColor: Color,
    secondaryColor: Color
) {
    var i = 0
    while (i < text.length) {
        when {
            // **bold**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }

            // `inline code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = secondaryColor,
                        fontSize = 12.sp,
                        background = Color(0x1AFFFFFF)
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            // [link text](url)
            text[i] == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket > i) closeBracket + 1 else -1
                val closeParen = if (openParen > 0 && openParen < text.length && text[openParen] == '(')
                    text.indexOf(')', openParen + 1) else -1

                if (closeBracket > i && closeParen > openParen) {
                    val linkText = text.substring(i + 1, closeBracket)
                    withStyle(SpanStyle(
                        color = Color(0xFF64B5F6),
                        textDecoration = TextDecoration.Underline
                    )) {
                        append(linkText)
                    }
                    i = closeParen + 1
                } else {
                    append(text[i])
                    i++
                }
            }

            // Plain character
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
