package com.bit.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.regex.Pattern

/**
 * Utility class for parsing various document formats into plain text.
 * Supports: PDF, EPUB, Excel (.xlsx), Word (.docx), PowerPoint (.pptx), ODF (.odt), and plain text files.
 * Custom lightweight ZIP/XML parsers are used to avoid heavy Apache POI dependencies.
 */
object DocumentParser {
    private const val TAG = "DocumentParser"
    @Volatile private var pdfBoxInitialized = false

    /**
     * Supported document MIME types
     */
    object MimeTypes {
        const val PDF = "application/pdf"
        const val EPUB = "application/epub+zip"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val XLS = "application/vnd.ms-excel"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val DOC = "application/msword"
        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val PPT = "application/vnd.ms-powerpoint"
        const val ODT = "application/vnd.oasis.opendocument.text"
    }

    /**
     * Parse a document from a URI into plain text.
     */
    suspend fun parseDocument(
        uri: Uri,
        context: Context,
        mimeType: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val detectedMimeType = mimeType ?: contentResolver.getType(uri)

            Log.d(TAG, "Parsing document: $uri, MIME type: $detectedMimeType")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val text = when (detectedMimeType) {
                    MimeTypes.PDF -> parsePdf(inputStream, context)
                    MimeTypes.EPUB -> parseEpub(inputStream)
                    MimeTypes.XLSX -> parseXlsx(inputStream)
                    MimeTypes.DOCX -> parseDocx(inputStream)
                    MimeTypes.PPTX -> parsePptx(inputStream)
                    MimeTypes.ODT -> parseOdt(inputStream)
                    MimeTypes.DOC -> parseDoc(inputStream)
                    MimeTypes.XLS -> parseXls(inputStream)
                    "text/plain" -> parsePlainText(inputStream)
                    else -> {
                        // Try to infer from file extension
                        val fileName = uri.lastPathSegment ?: ""
                        when {
                            fileName.endsWith(".pdf", ignoreCase = true) -> parsePdf(inputStream, context)
                            fileName.endsWith(".epub", ignoreCase = true) -> parseEpub(inputStream)
                            fileName.endsWith(".xlsx", ignoreCase = true) -> parseXlsx(inputStream)
                            fileName.endsWith(".docx", ignoreCase = true) -> parseDocx(inputStream)
                            fileName.endsWith(".pptx", ignoreCase = true) -> parsePptx(inputStream)
                            fileName.endsWith(".odt", ignoreCase = true) -> parseOdt(inputStream)
                            fileName.endsWith(".doc", ignoreCase = true) -> parseDoc(inputStream)
                            fileName.endsWith(".xls", ignoreCase = true) -> parseXls(inputStream)
                            fileName.endsWith(".txt", ignoreCase = true) -> parsePlainText(inputStream)
                            else -> {
                                Log.w(TAG, "Unknown file type: $detectedMimeType / $fileName, treating as plain text")
                                parsePlainText(inputStream)
                            }
                        }
                    }
                }

                Log.d(TAG, "Successfully parsed document, extracted ${text.length} characters")
                Result.success(text)
            } ?: Result.failure(Exception("Failed to open input stream for URI: $uri"))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing document: ${e.message}", e)
            Result.failure(Exception("Failed to parse document: ${e.message}", e))
        }
    }

    /**
     * Unescape XML entity characters (e.g. &amp;, &lt;, &gt;, &quot;, &apos;).
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#x9;", "\t")
            .replace("&#xA;", "\n")
            .replace("&#xD;", "\r")
            .replace(Regex("&#(\\d+);")) { matchResult ->
                val code = matchResult.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else matchResult.value
            }
    }

    /**
     * Parse a PDF document using PDFBox-Android
     */
    private fun parsePdf(inputStream: InputStream, context: Context): String {
        return try {
            if (!pdfBoxInitialized) {
                synchronized(this) {
                    if (!pdfBoxInitialized) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        pdfBoxInitialized = true
                    }
                }
            }

            PDDocument.load(inputStream).use { document ->
                val stripper = PDFTextStripper()
                stripper.getText(document) ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PDF: ${e.message}", e)
            throw Exception("Failed to parse PDF: ${e.message}", e)
        }
    }

    /**
     * Parse an EPUB document
     */
    private fun parseEpub(inputStream: InputStream): String {
        return try {
            val textBuilder = StringBuilder()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name ?: ""
                    if (entryName.endsWith(".xhtml", ignoreCase = true) ||
                        entryName.endsWith(".html", ignoreCase = true) ||
                        entryName.endsWith(".htm", ignoreCase = true)
                    ) {
                        val html = zip.bufferedReader(Charsets.UTF_8).readText()
                        val doc = Jsoup.parse(html)
                        val cleanText = doc.text().trim()
                        if (cleanText.isNotBlank()) {
                            textBuilder.append(cleanText).append("\n\n")
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            textBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB: ${e.message}", e)
            throw Exception("Failed to parse EPUB: ${e.message}", e)
        }
    }

    /**
     * Parse a Word .docx file (Office Open XML format)
     */
    private fun parseDocx(inputStream: InputStream): String {
        return try {
            val textBuilder = StringBuilder()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name ?: ""
                    if (entryName == "word/document.xml") {
                        val content = zip.bufferedReader(Charsets.UTF_8).readText()
                        val pMatcher = Pattern.compile("<w:p[^>]*>(.*?)</w:p>").matcher(content)
                        while (pMatcher.find()) {
                            val pContent = pMatcher.group(1) ?: ""
                            val tMatcher = Pattern.compile("<w:t[^>]*>(.*?)</w:t>").matcher(pContent)
                            val paragraphText = StringBuilder()
                            while (tMatcher.find()) {
                                paragraphText.append(unescapeXml(tMatcher.group(1) ?: ""))
                            }
                            if (paragraphText.isNotEmpty()) {
                                textBuilder.append(paragraphText).append("\n")
                            }
                        }
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            textBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DOCX: ${e.message}", e)
            throw Exception("Failed to parse Word document (.docx): ${e.message}", e)
        }
    }

    /**
     * Parse an Excel .xlsx file (Office Open XML format)
     */
    private fun parseXlsx(inputStream: InputStream): String {
        return try {
            val sharedStrings = mutableListOf<String>()
            val textBuilder = StringBuilder()
            val entries = mutableMapOf<String, ByteArray>()

            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name ?: ""
                    if (!entry.isDirectory && entryName.isNotEmpty()) {
                        entries[entryName] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }

            // 1. Shared Strings Table
            val sharedStringsBytes = entries["xl/sharedStrings.xml"]
            if (sharedStringsBytes != null) {
                val content = String(sharedStringsBytes, Charsets.UTF_8)
                val matcher = Pattern.compile("<t[^>]*>(.*?)</t>").matcher(content)
                while (matcher.find()) {
                    sharedStrings.add(unescapeXml(matcher.group(1) ?: ""))
                }
            }

            // 2. Sheets
            entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .forEach { sheetKey ->
                    val sheetBytes = entries[sheetKey] ?: return@forEach
                    val content = String(sheetBytes, Charsets.UTF_8)

                    val rowMatcher = Pattern.compile("<row[^>]*>(.*?)</row>").matcher(content)
                    while (rowMatcher.find()) {
                        val rowContent = rowMatcher.group(1) ?: ""
                        val cellMatcher = Pattern.compile("<c[^>]*>(.*?)</c>").matcher(rowContent)
                        val rowCells = mutableListOf<String>()
                        while (cellMatcher.find()) {
                            val cellContent = cellMatcher.group(0) ?: ""
                            val vMatcher = Pattern.compile("<v>(.*?)</v>").matcher(cellContent)
                            if (vMatcher.find()) {
                                val rawVal = vMatcher.group(1) ?: ""
                                if (cellContent.contains("t=\"s\"") || cellContent.contains("t='s'")) {
                                    val idx = rawVal.toIntOrNull()
                                    if (idx != null && idx >= 0 && idx < sharedStrings.size) {
                                        rowCells.add(sharedStrings[idx])
                                    } else {
                                        rowCells.add(rawVal)
                                    }
                                } else {
                                    rowCells.add(rawVal)
                                }
                            }
                        }
                        if (rowCells.isNotEmpty()) {
                            textBuilder.append(rowCells.joinToString("\t")).append("\n")
                        }
                    }
                    textBuilder.append("\n")
                }

            textBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing XLSX: ${e.message}", e)
            throw Exception("Failed to parse Excel file (.xlsx): ${e.message}", e)
        }
    }

    /**
     * Parse a PowerPoint .pptx file
     */
    private fun parsePptx(inputStream: InputStream): String {
        return try {
            val textBuilder = StringBuilder()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name ?: ""
                    if (entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml")) {
                        val content = zip.bufferedReader(Charsets.UTF_8).readText()
                        val tMatcher = Pattern.compile("<a:t[^>]*>(.*?)</a:t>").matcher(content)
                        val slideText = StringBuilder()
                        while (tMatcher.find()) {
                            slideText.append(unescapeXml(tMatcher.group(1) ?: "")).append(" ")
                        }
                        if (slideText.isNotEmpty()) {
                            val slideName = entryName.substringAfterLast("/").substringBefore(".")
                            textBuilder.append("Slide [").append(slideName).append("]: ").append(slideText.toString().trim()).append("\n\n")
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            textBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PPTX: ${e.message}", e)
            throw Exception("Failed to parse PowerPoint document (.pptx): ${e.message}", e)
        }
    }

    /**
     * Parse an ODF Text document (.odt)
     */
    private fun parseOdt(inputStream: InputStream): String {
        return try {
            val textBuilder = StringBuilder()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name ?: ""
                    if (entryName == "content.xml") {
                        val content = zip.bufferedReader(Charsets.UTF_8).readText()
                        val matcher = Pattern.compile("<text:[ph][^>]*>(.*?)</text:[ph]>").matcher(content)
                        while (matcher.find()) {
                            val rawText = (matcher.group(1) ?: "").replace(Regex("<[^>]*>"), "")
                            textBuilder.append(unescapeXml(rawText)).append("\n")
                        }
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            textBuilder.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ODT: ${e.message}", e)
            throw Exception("Failed to parse OpenDocument (.odt): ${e.message}", e)
        }
    }

    /**
     * Legacy Word format (.doc) placeholder
     */
    private fun parseDoc(inputStream: InputStream): String {
        throw Exception("Legacy binary word format (.doc) is not supported offline. Please save as .docx and re-import.")
    }

    /**
     * Legacy Excel format (.xls) placeholder
     */
    private fun parseXls(inputStream: InputStream): String {
        throw Exception("Legacy binary excel format (.xls) is not supported offline. Please save as .xlsx and re-import.")
    }

    /**
     * Parse a plain text file
     */
    private fun parsePlainText(inputStream: InputStream): String {
        return try {
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing plain text: ${e.message}", e)
            throw Exception("Failed to parse plain text: ${e.message}", e)
        }
    }

    /**
     * Get human-readable file type name from MIME type
     */
    fun getFileTypeName(mimeType: String?): String {
        val mime = mimeType ?: ""
        return when (mime) {
            MimeTypes.PDF -> "PDF"
            MimeTypes.EPUB -> "EPUB"
            MimeTypes.XLSX, MimeTypes.XLS -> "Excel"
            MimeTypes.DOCX, MimeTypes.DOC -> "Word"
            MimeTypes.PPTX, MimeTypes.PPT -> "PowerPoint"
            MimeTypes.ODT -> "OpenDocument"
            "text/plain" -> "Text"
            else -> "Document"
        }
    }
}