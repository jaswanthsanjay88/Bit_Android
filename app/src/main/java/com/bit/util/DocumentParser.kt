package com.bit.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

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
     * Get display file name from Content URI or last path segment.
     */
    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (columnIndex != -1) {
                            name = cursor.getString(columnIndex) ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve display name from URI", e)
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment ?: ""
        }
        return name
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
            val fileName = getFileName(context, uri)

            Log.d(TAG, "Parsing document: $uri, Display Name: $fileName, MIME type: $detectedMimeType")

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Failed to read bytes from URI: $uri"))

            // 1. Magic Bytes Check for PDF (%PDF)
            val isPdfMagic = bytes.size >= 4 &&
                bytes[0] == 0x25.toByte() && // '%'
                bytes[1] == 0x50.toByte() && // 'P'
                bytes[2] == 0x44.toByte() && // 'D'
                bytes[3] == 0x46.toByte()    // 'F'

            val isPdfMimeOrExt = detectedMimeType == MimeTypes.PDF ||
                detectedMimeType?.contains("pdf", ignoreCase = true) == true ||
                fileName.endsWith(".pdf", ignoreCase = true)

            val text = when {
                isPdfMagic || isPdfMimeOrExt -> parsePdf(bytes, context)
                detectedMimeType == MimeTypes.EPUB || fileName.endsWith(".epub", ignoreCase = true) -> parseEpub(ByteArrayInputStream(bytes))
                detectedMimeType == MimeTypes.XLSX || fileName.endsWith(".xlsx", ignoreCase = true) -> parseXlsx(ByteArrayInputStream(bytes))
                detectedMimeType == MimeTypes.DOCX || fileName.endsWith(".docx", ignoreCase = true) -> parseDocx(ByteArrayInputStream(bytes))
                detectedMimeType == MimeTypes.PPTX || fileName.endsWith(".pptx", ignoreCase = true) -> parsePptx(ByteArrayInputStream(bytes))
                detectedMimeType == MimeTypes.ODT || fileName.endsWith(".odt", ignoreCase = true) -> parseOdt(ByteArrayInputStream(bytes))
                detectedMimeType == MimeTypes.DOC || fileName.endsWith(".doc", ignoreCase = true) -> parseDoc()
                detectedMimeType == MimeTypes.XLS || fileName.endsWith(".xls", ignoreCase = true) -> parseXls()
                else -> parsePlainText(bytes)
            }

            Log.d(TAG, "Successfully parsed document, extracted ${text.length} characters")
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing document: ${e.message}", e)
            Result.failure(Exception("Failed to parse document: ${e.message}", e))
        }
    }

    /**
     * Unescape XML entity characters.
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
     * Parse a PDF document using PDFBox-Android safely from byte array.
     */
    private fun parsePdf(bytes: ByteArray, context: Context): String {
        return try {
            if (!pdfBoxInitialized) {
                synchronized(this) {
                    if (!pdfBoxInitialized) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        pdfBoxInitialized = true
                    }
                }
            }

            ByteArrayInputStream(bytes).use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PDF with PDFBox: ${e.message}", e)
            "[PDF Document: Unable to extract text. The PDF may contain scanned images or encrypted formatting.]"
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

    private fun parseDoc(): String {
        throw Exception("Legacy binary word format (.doc) is not supported offline. Please save as .docx and re-import.")
    }

    private fun parseXls(): String {
        throw Exception("Legacy binary excel format (.xls) is not supported offline. Please save as .xlsx and re-import.")
    }

    /**
     * Parse a plain text file safely replacing invalid UTF-8 sequences.
     */
    private fun parsePlainText(bytes: ByteArray): String {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            Log.w(TAG, "UTF-8 safe decoding failed, falling back to ISO-8859-1", e)
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /**
     * Get human-readable file type name from MIME type
     */
    fun getFileTypeName(mimeType: String?): String {
        val mime = mimeType ?: ""
        return when {
            mime == MimeTypes.PDF || mime.contains("pdf", ignoreCase = true) -> "PDF"
            mime == MimeTypes.EPUB -> "EPUB"
            mime == MimeTypes.XLSX || mime == MimeTypes.XLS -> "Excel"
            mime == MimeTypes.DOCX || mime == MimeTypes.DOC -> "Word"
            mime == MimeTypes.PPTX || mime == MimeTypes.PPT -> "PowerPoint"
            mime == MimeTypes.ODT -> "OpenDocument"
            mime == "text/plain" -> "Text"
            else -> "Document"
        }
    }
}