package com.bit.ui.components.markdown

import android.util.Base64
import java.nio.charset.StandardCharsets

object MarkdownPreprocessor {

    const val LATEX_URL_PREFIX = "latex://"

    /**
     * Complete preprocessing pipeline:
     * 1. Protects code blocks byte-for-byte.
     * 2. Detects LaTeX spans and converts them into latex:// markdown image links.
     * 3. Protects literal angle-bracket tags (<widget>, <T>) using zero-width spaces.
     * 4. Escapes remaining standalone $ signs.
     */
    fun toRenderableMarkdownText(raw: String): String {
        if (raw.isBlank()) return raw

        // Step 1: Extract code blocks to avoid mutating contents inside ``` or `
        val (textWithoutCode, codePlaceholders) = extractCodeBlocks(raw)

        // Step 2: Parse LaTeX spans and convert to markdown image links
        val textWithLatexImages = parseLatexSpansToMarkdown(textWithoutCode)

        // Step 3: Protect literal angle-bracket tags
        val textWithProtectedTags = protectLiteralAngleBracketTags(textWithLatexImages)

        // Step 4: Escape unhandled dollars
        val textEscaped = escapeDollarForMarkdown(textWithProtectedTags)

        // Step 5: Restore original code blocks untouched
        return restoreCodeBlocks(textEscaped, codePlaceholders)
    }

    /**
     * Encodes a LaTeX expression into a latex:// URL string.
     */
    fun encodeLatexUrl(latex: String, isDisplay: Boolean): String {
        val encoded = Base64.encodeToString(latex.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val mode = if (isDisplay) "display" else "inline"
        return "$LATEX_URL_PREFIX$mode/$encoded"
    }

    /**
     * Decodes a latex:// URL string back into (latexExpression, isDisplay).
     */
    fun decodeLatexUrl(url: String): Pair<String, Boolean>? {
        if (!url.startsWith(LATEX_URL_PREFIX)) return null
        val path = url.removePrefix(LATEX_URL_PREFIX)
        val parts = path.split("/", limit = 2)
        if (parts.size != 2) return null

        val isDisplay = parts[0] == "display"
        val base64 = parts[1]
        return try {
            val bytes = Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            Pair(String(bytes, StandardCharsets.UTF_8), isDisplay)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLatexSpansToMarkdown(text: String): String {
        val result = StringBuilder()
        var i = 0
        val len = text.length

        while (i < len) {
            when {
                // Block math $$ ... $$
                text.startsWith("$$", i) -> {
                    val end = text.indexOf("$$", i + 2)
                    if (end != -1) {
                        val expr = text.substring(i + 2, end).trim()
                        val imgUrl = encodeLatexUrl(expr, isDisplay = true)
                        result.append("\n\n![latex]($imgUrl)\n\n")
                        i = end + 2
                    } else {
                        result.append(text[i])
                        i++
                    }
                }
                // Block math \[ ... \]
                text.startsWith("\\[", i) -> {
                    val end = text.indexOf("\\]", i + 2)
                    if (end != -1) {
                        val expr = text.substring(i + 2, end).trim()
                        val imgUrl = encodeLatexUrl(expr, isDisplay = true)
                        result.append("\n\n![latex]($imgUrl)\n\n")
                        i = end + 2
                    } else {
                        result.append(text[i])
                        i++
                    }
                }
                // Inline math \( ... \)
                text.startsWith("\\(", i) -> {
                    val end = text.indexOf("\\)", i + 2)
                    if (end != -1) {
                        val expr = text.substring(i + 2, end).trim()
                        val imgUrl = encodeLatexUrl(expr, isDisplay = false)
                        result.append(" ![latex]($imgUrl) ")
                        i = end + 2
                    } else {
                        result.append(text[i])
                        i++
                    }
                }
                // Single $ ... $ (must not be preceded by number/currency or followed by space)
                text[i] == '$' && (i == 0 || text[i - 1] != '$') -> {
                    val end = findInlineDollarEnd(text, i + 1)
                    if (end != -1) {
                        val expr = text.substring(i + 1, end).trim()
                        val imgUrl = encodeLatexUrl(expr, isDisplay = false)
                        result.append(" ![latex]($imgUrl) ")
                        i = end + 1
                    } else {
                        result.append(text[i])
                        i++
                    }
                }
                else -> {
                    result.append(text[i])
                    i++
                }
            }
        }
        return result.toString()
    }

    private fun findInlineDollarEnd(text: String, startFrom: Int): Int {
        var idx = startFrom
        while (idx < text.length) {
            if (text[idx] == '$' && (idx == 0 || text[idx - 1] != '\\')) {
                val content = text.substring(startFrom, idx)
                if (content.isNotBlank() && !content.contains("\n\n")) {
                    return idx
                }
            }
            idx++
        }
        return -1
    }

    /**
     * Protects plain angle bracket tags like <widget> or <T> by inserting a zero-width space after <.
     * Leaves valid markdown autolinks like <https://...> intact.
     */
    fun protectLiteralAngleBracketTags(text: String): String {
        val pattern = Regex("""<([a-zA-Z_][a-zA-Z0-9_\-\.]*+)([^>]*+)>""")
        return pattern.replace(text) { match ->
            val fullTag = match.value
            if (fullTag.startsWith("<http://") || fullTag.startsWith("<https://") ||
                fullTag.startsWith("<mailto:") || isStandardHtmlTag(match.groupValues[1])
            ) {
                fullTag
            } else {
                "<\u200B${match.groupValues[1]}${match.groupValues[2]}>"
            }
        }
    }

    private fun isStandardHtmlTag(tagName: String): Boolean {
        val standard = setOf("b", "i", "u", "s", "p", "br", "hr", "a", "img", "span", "div", "code", "pre", "table", "tr", "td", "th", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6")
        return standard.contains(tagName.lowercase())
    }

    private fun escapeDollarForMarkdown(text: String): String {
        return text.replace(Regex("""(?<!\\)\$"""), "\\$")
    }

    private fun extractCodeBlocks(text: String): Pair<String, List<String>> {
        val placeholders = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0

        while (i < text.length) {
            if (text.startsWith("```", i)) {
                val end = text.indexOf("```", i + 3)
                if (end != -1) {
                    val codeBlock = text.substring(i, end + 3)
                    placeholders.add(codeBlock)
                    sb.append("___CODE_BLOCK_${placeholders.size - 1}___")
                    i = end + 3
                    continue
                }
            } else if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1 && !text.substring(i + 1, end).contains("\n")) {
                    val inlineCode = text.substring(i, end + 1)
                    placeholders.add(inlineCode)
                    sb.append("___CODE_BLOCK_${placeholders.size - 1}___")
                    i = end + 1
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        return Pair(sb.toString(), placeholders)
    }

    private fun restoreCodeBlocks(text: String, placeholders: List<String>): String {
        var restored = text
        placeholders.forEachIndexed { index, code ->
            restored = restored.replace("___CODE_BLOCK_${index}___", code)
        }
        return restored
    }
}
